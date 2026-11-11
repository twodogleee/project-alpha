package com._36knight.alpha;

import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.PrdDocuments;
import com._36knight.alpha.workspace.PrdStructure;
import com._36knight.alpha.workspace.WorkbenchContracts.Reply;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 模块增量合并与旧 JSON 读取的边界，不依赖模型猜测结构。@author Andersen */
class PrdDocumentsTest {
    /** 保存于旧版本且没有新增属性的 JSON 可以无损读取。 */
    @Test void legacyJsonKeepsOriginalTextWithoutInventedModules() throws Exception {
        var json=new ObjectMapper();var original=blank();
        var node=json.valueToTree(original);((com.fasterxml.jackson.databind.node.ObjectNode)node).remove(List.of("templateVersion","modules","issueLinks"));
        var decoded=json.treeToValue(node,Prd.class);
        assertThat(decoded).isEqualTo(original);assertThat(decoded.templateVersion()).isEqualTo(1);
        assertThat(decoded.modules()).isEmpty();
    }
    /** 改名保留稳定标识和正文，删除必须显式指定且不能携带修改正文。 */
    @Test void structureChangesPreserveSiblingsAndRejectDanglingWrites() {
        var p=fixture();
        var renamed=PrdDocuments.apply(p,new Reply("改名",null,null,null,List.of(new PrdStructure.ModuleChange("people","资源管理",false,List.of(new PrdStructure.PageChange("list","资源列表",null,false))))));
        assertThat(renamed.modules().getFirst().pages().getFirst().sections()).containsEntry("fields","人员姓名");
        assertThat(renamed.modules().getFirst().pages().get(1)).isEqualTo(p.modules().getFirst().pages().get(1));
        assertThat(PrdDocuments.changedSections(p,renamed)).contains("module:people","page:people:list");
        var delete=List.of(new PrdStructure.ModuleChange("people",null,false,List.of(new PrdStructure.PageChange("list",null,null,true))));
        var removed=PrdDocuments.apply(p,new Reply("删除页面",null,null,null,delete));
        assertThat(removed.modules().getFirst().pages()).extracting(PrdStructure.Page::id).containsExactly("form");
        assertThatThrownBy(()->PrdDocuments.apply(p,new Reply("错误补丁",Map.of("page:people:list:fields","不存在"),null,null,delete))).isInstanceOf(IllegalArgumentException.class);
        assertThat(p.modules().getFirst().pages()).hasSize(2);
    }
    /** 非存在对象和未知正文键不可由正文补丁隐式创建。 */
    @Test void rejectsUnknownKeysAndDuplicateStructureEdits() {
        var p=fixture();
        assertThatThrownBy(()->PrdDocuments.patch(p,Map.of("page:people:list:sql","无效"),null)).isInstanceOf(IllegalArgumentException.class);
        var change=new PrdStructure.ModuleChange("people","人员",false,null);
        assertThatThrownBy(()->PrdDocuments.apply(p,new Reply("重复",null,null,null,List.of(change,change)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PrdDocuments.apply(p,new Reply("缺名称",null,null,null,List.of(new PrdStructure.ModuleChange("missing",null,false,null))))).isInstanceOf(IllegalArgumentException.class);
    }
    /** 页面类型变化不删除已有字段，待确认事项保持原样。 */
    @Test void changingPageTypePreservesContentAndQuestions() {
        var p=PrdDocuments.patch(fixture(),null,List.of("Q01 权限待确认"));
        var result=PrdDocuments.apply(p,new Reply("改为详情",null,null,null,List.of(new PrdStructure.ModuleChange("people",null,false,List.of(new PrdStructure.PageChange("list",null,"DETAIL",false))))));
        assertThat(result.modules().getFirst().pages().getFirst().sections()).containsEntry("fields","人员姓名");
        assertThat(result.unresolvedIssues()).containsExactly("Q01 权限待确认");
        assertThat(PrdDocuments.blockers(result)).noneMatch(text->text.contains("排序与分页"));
    }
    /** 问题关联使用稳定章节路径，改名保留、删除节点解绑、解决问题清理。 */
    @Test void issueLinksSurviveRenameAndCleanUpResolvedOrRemovedRules() {
        String issue="Q01 部门包含下级吗？", path="page:people:list:search";
        var original=PrdDocuments.patch(fixture(),null,List.of(issue));
        var linked=PrdDocuments.apply(original,new Reply("追问",null,null,null,null,Map.of(issue,List.of(path,"page:people:list:acceptance"))));
        var renamed=PrdDocuments.apply(linked,new Reply("改名",null,null,null,List.of(new PrdStructure.ModuleChange("people","组织人员",false,null))));
        assertThat(renamed.issueLinks()).isEqualTo(linked.issueLinks());
        assertThat(PrdDocuments.patch(linked,Map.of(path,"包含下级"),List.of()).issueLinks()).isEmpty();
        var removed=PrdDocuments.apply(linked,new Reply("删除",null,null,null,List.of(new PrdStructure.ModuleChange("people",null,false,List.of(new PrdStructure.PageChange("list",null,null,true))))));
        assertThat(removed.unresolvedIssues()).containsExactly(issue);
        assertThat(removed.issueLinks().get(issue)).isEmpty();
        assertThat(PrdDocuments.changedSections(original,linked)).contains("issues");
    }
    /** 非存在问题与规则都不能通过结构化模型返回写入。 */
    @Test void rejectsDanglingIssueLinks() {
        var p=PrdDocuments.patch(fixture(),null,List.of("Q01"));
        assertThatThrownBy(()->PrdDocuments.apply(p,new Reply("错误",null,null,null,null,Map.of("Q01",List.of("page:people:missing:rules"))))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PrdDocuments.apply(p,new Reply("错误",null,null,null,null,Map.of("Q02",List.of("rules"))))).isInstanceOf(IllegalArgumentException.class);
    }
    /** 空旧稿用于验证格式兼容。 */
    private Prd blank(){return new Prd("背景","目标","范围","流程","规则","异常","验收","边界","排除",List.of(),List.of());}
    /** 合成模块包含列表和表单两个独立页面。 */
    private Prd fixture(){return PrdDocuments.withModules(blank(),List.of(new PrdStructure.Module("people","人员管理",Map.of(),List.of(new PrdStructure.Page("list","人员列表","LIST",Map.of("fields","人员姓名")),new PrdStructure.Page("form","新增人员","FORM",Map.of("form","姓名必填"))))));}
}
