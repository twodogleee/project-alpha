package com._36knight.alpha;

import com._36knight.alpha.controller.PrdTemplateController;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.PrdDocuments;
import com._36knight.alpha.workspace.PrdStructure;
import com._36knight.alpha.workspace.PrdTemplates;
import com._36knight.alpha.workspace.WorkbenchContracts.Reply;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 模板绑定、兼容解析、定稿要求及只读目录契约，全部使用合成文档。 */
class PrdTemplatesTest {
    /** 三种版本化预设共享标准章节和页面类型，但有各自写作引导及默认页面。 */
    @Test void exposesThreeImmutableTemplatesWithDistinctProductGuidance() {
        var catalog=PrdTemplates.catalog();
        assertThat(catalog.templates()).extracting(PrdTemplates.Definition::id).containsExactly("management","mobile","general");
        assertThat(catalog.templates()).extracting(PrdTemplates.Definition::defaultPageType).containsExactly("LIST","HOME","CUSTOM");
        for(var template:catalog.templates()) {
            assertThat(PrdTemplates.titles(template.globalSections()).keySet()).isEqualTo(PrdDocuments.TITLES.keySet());
            assertThat(PrdTemplates.titles(template.moduleSections()).keySet()).isEqualTo(PrdDocuments.MODULE_TITLES.keySet());
            assertThat(PrdTemplates.titles(template.pageSections()).keySet()).isEqualTo(PrdDocuments.PAGE_TITLES.keySet());
            assertThat(template.pageTypes()).hasSize(11);
        }
        assertThat(PrdTemplates.resolve("mobile",1).guidance()).contains("弱网","系统授权");
        assertThatThrownBy(()->catalog.templates().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->PrdTemplates.resolve("mobile",1).pageTypes().getFirst().requiredSections().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    /** 历史 JSON 没有模板引用时固定管理系统首版，不按当前目录排序或最新版本推断。 */
    @Test void legacyJsonBindsExplicitFrozenVersionAndRoundTrips() throws Exception {
        var json=new ObjectMapper();var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(document());
        node.remove(List.of("templateId","templateRevision"));
        var old=json.treeToValue(node,Prd.class);
        assertThat(old.templateId()).isEqualTo("management");assertThat(old.templateRevision()).isEqualTo(1);
        assertThat(json.readValue(json.writeValueAsString(old),Prd.class)).isEqualTo(old);
        assertThat(PrdTemplates.forDocument(old)).isSameAs(PrdTemplates.resolve("management",1));
    }
    /** 模板切换只改变引用；未推荐的搜索正文、空规则关联和需求来源都必须保留。 */
    @Test void switchingPreservesTextAndIssueLinksAcrossAllMergePaths() {
        var before=document();var mobile=PrdDocuments.withTemplate(before,"mobile",1);
        assertThat(PrdDocuments.sections(mobile)).isEqualTo(PrdDocuments.sections(before));
        assertThat(mobile.modules()).isEqualTo(before.modules());assertThat(mobile.issueLinks()).isEqualTo(before.issueLinks());
        assertThat(mobile.requirementIds()).isEqualTo(before.requirementIds());assertThat(mobile.unresolvedIssues()).isEqualTo(before.unresolvedIssues());
        assertThat(PrdDocuments.changedSections(before,mobile)).contains("template");
        var patched=PrdDocuments.apply(mobile,new Reply("修改",Map.of("page:people:list:fields","头像与姓名"),null,null,
                List.of(new PrdStructure.ModuleChange("people","通讯录",false,null))));
        assertThat(patched.templateId()).isEqualTo("mobile");assertThat(patched.templateRevision()).isEqualTo(1);
        assertThat(patched.issueLinks()).isEqualTo(before.issueLinks());
        assertThat(PrdDocuments.labels(patched)).containsEntry("page:people:list:fields","通讯录 / 人员列表 / 内容与展示元素");
        assertThat(PrdDocuments.withIssueLinks(patched,patched.issueLinks()).templateId()).isEqualTo("mobile");
    }
    /** 未发布版本和无效页面类型不能通过静默回退绕过保存校验。 */
    @Test void rejectsUnknownVersionsAndPageTypes() {
        assertThatThrownBy(()->PrdTemplates.resolve("missing",1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PrdTemplates.resolve("mobile",2)).isInstanceOf(IllegalArgumentException.class);
        var unknown=new Prd("","","","","","","","","",List.of(),List.of(),2,List.of(),Map.of(),"mobile",999);
        assertThatThrownBy(()->PrdDocuments.validateStructure(unknown)).isInstanceOf(IllegalArgumentException.class);
        var invalid=PrdDocuments.withModules(document(),List.of(new PrdStructure.Module("x","模块",Map.of(),List.of(new PrdStructure.Page("x","页面","EXECUTE_SCRIPT",Map.of())))));
        assertThatThrownBy(()->PrdDocuments.validateStructure(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    /** App 首页无需搜索排序，通用产品允许按需补权限；列表仍保留查询要求。 */
    @Test void finalizationUsesBoundTemplateAndActualPagePurpose() {
        var mobile=PrdTemplates.resolve("mobile",1);
        assertThat(mobile.requiredPageSections("HOME")).contains("fields","interactions","states").doesNotContain("search","sorting");
        assertThat(mobile.requiredPageSections("LIST")).contains("search","sorting");
        var general=PrdDocuments.withTemplate(document(),"general",1);
        assertThat(PrdDocuments.blockers(general)).noneMatch(text->text.contains("权限与数据范围"));
        assertThat(PrdDocuments.blockers(document())).anyMatch(text->text.contains("权限与数据范围"));
    }
    /** 九章节旧稿显式切换后采用新显示与必填项，但不凭空增加模块或改写正文。 */
    @Test void legacyStructureUsesSelectedTemplateWithoutAutomaticStructureMigration() {
        var old=new Prd("背景","目标","范围","流程","规则","","","边界","排除",List.of(),List.of());
        assertThat(PrdDocuments.labels(old)).containsEntry("objectives","目标");
        assertThat(PrdDocuments.blockers(old)).containsExactly("补充异常路径","补充验收标准");
        var mobile=PrdDocuments.withTemplate(old,"mobile",1);
        assertThat(mobile.templateVersion()).isEqualTo(1);assertThat(mobile.modules()).isEmpty();
        assertThat(PrdDocuments.sections(mobile)).isEqualTo(PrdDocuments.sections(old));
        assertThat(PrdDocuments.labels(mobile)).containsEntry("objectives","目标与用户场景");
        assertThat(PrdDocuments.blockers(mobile)).isEmpty();
    }
    /** 系统配置公开目录与业务代码使用相同资源；无编辑接口。 */
    @Test void readOnlyApiReturnsCanonicalCatalog() throws Exception {
        var service=org.mockito.Mockito.mock(com._36knight.alpha.template.PrdTemplateService.class);
        org.mockito.Mockito.when(service.catalog()).thenReturn(PrdTemplates.catalog());
        var mvc=MockMvcBuilders.standaloneSetup(new PrdTemplateController(service)).build();
        mvc.perform(get("/api/alpha/config/prd-templates")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templates.length()").value(3))
                .andExpect(jsonPath("$.data.templates[1].id").value("mobile"))
                .andExpect(jsonPath("$.data.templates[1].revision").value(1))
                .andExpect(jsonPath("$.data.legacyGlobalSections.length()").value(9));
    }
    /** 合成列表包含可选规则的空关联，用于验证切换不做清理或推断迁移。 */
    private Prd document() {
        var page=new PrdStructure.Page("list","人员列表","LIST",Map.of("fields","姓名","search","按姓名查询"));
        var module=new PrdStructure.Module("people","人员管理",Map.of("overview","人员维护"),List.of(page));
        return new Prd("背景","目标","范围","流程","规则","异常","验收","边界","排除",List.of("r1"),List.of("Q01 授权待确认"),2,List.of(module),Map.of("Q01 授权待确认",List.of("page:people:list:permissions")));
    }
}
