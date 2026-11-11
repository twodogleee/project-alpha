package com._36knight.alpha;

import com._36knight.alpha.prototype.*;
import com._36knight.alpha.template.PrdTemplateService;
import com._36knight.alpha.workspace.*;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static com._36knight.alpha.prototype.PrototypeContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 冻结来源按复合页面身份追踪，摘要不压缩正文语义或伪造依赖完整性。@author Andersen */
class PrototypeSourcesTest {
    /** 业务时间和记录沿用应用 JSON 模块。 */
    private final PrototypeJson json=new PrototypeJson(new ObjectMapper().findAndRegisterModules());
    /** 合成文档共享已知模板，测试不连接数据库或模型。 */
    private PrototypeSources sources(){var templates=mock(PrdTemplateService.class);when(templates.forDocument(any())).thenAnswer(call->PrdTemplates.forDocument(call.getArgument(0)));return new PrototypeSources(mock(LifecycleRepository.class),templates,json);}

    /** 不同模块的同名页面必须保留两份身份，子集来源仍冻结完整文档。 */
    @Test void repeatedPageIdsAcrossModulesStayDistinctAndSnapshotKeepsWholeDocument(){
        var sources=sources();var prd=prd();var all=sources.scope(prd,new Scope("ALL",List.of()));
        assertThat(all.pageRefs()).containsExactly(new PageRef("people","list"),new PageRef("orders","list"));
        var source=sources.freeze("prototype",project(),version(),state(prd),new Source("DRAFT",null),new Scope("SELECTED",List.of(new PageRef("people","list"))));
        assertThat(json.decode(source.prdJson,Prd.class).modules()).hasSize(2);
        assertThat(json.tree(source.fingerprintsJson).path("pages").has("people/list")).isTrue();
        assertThat(json.tree(source.fingerprintsJson).path("pages").has("orders/list")).isFalse();
        assertThat(source.contextJson).contains("writingTemplate","background");
    }
    /** 所选依赖改变直接过期，其他模块变化保守要求复核，单纯显示名称变化不污染语义。 */
    @Test void distinguishesKnownDependencyChangesFromUncertainUnselectedChanges(){
        var sources=sources();var project=project();var version=version();var state=state(prd());
        var source=sources.freeze("prototype",project,version,state,new Source("DRAFT",null),new Scope("SELECTED",List.of(new PageRef("people","list"))));
        assertThat(sources.freshness(source,project,version,state)).isEqualTo("CURRENT");
        project.name="改了显示名称";project.revision++;
        assertThat(sources.freshness(source,project,version,state)).isEqualTo("CURRENT");
        state.prdJson=json.encode(PrdDocuments.patch(prd(),Map.of("page:orders:list:fields","订单新增金额"),null));
        assertThat(sources.freshness(source,project,version,state)).isEqualTo("REVIEW_REQUIRED");
        state.prdJson=json.encode(PrdDocuments.patch(prd(),Map.of("page:people:list:fields","人员新增手机号"),null));
        assertThat(sources.freshness(source,project,version,state)).isEqualTo("STALE");
        state.prdJson=json.encode(prd());project.background="新的业务背景";
        assertThat(sources.freshness(source,project,version,state)).isEqualTo("STALE");
    }
    /** 对象键顺序无关，数组顺序和文本空白都是实际输入的一部分。 */
    @Test void canonicalHashPreservesArrayOrderAndTextWhitespace(){
        assertThat(json.hash(json.tree("{\"b\":2,\"a\":1}"))).isEqualTo(json.hash(json.tree("{\"a\":1,\"b\":2}")));
        assertThat(json.hash(List.of("甲","乙"))).isNotEqualTo(json.hash(List.of("乙","甲")));
        assertThat(json.hash(Map.of("text","甲\n乙"))).isNotEqualTo(json.hash(Map.of("text","甲 乙")));
    }
    /** 两模块有意复用 list，检验当前 PRD 已允许的局部 ID 语义。 */
    private Prd prd(){return PrdDocuments.withModules(new Prd("背景","目标","范围","流程","规则","异常","验收","质量","排除",List.of(),List.of()),List.of(
            new PrdStructure.Module("people","人员",Map.of(),List.of(new PrdStructure.Page("list","列表","LIST",Map.of("fields","姓名")))),
            new PrdStructure.Module("orders","订单",Map.of(),List.of(new PrdStructure.Page("list","列表","LIST",Map.of("fields","编号"))))));}
    /** 只含实际冻结所需的合成父项目字段。 */
    private WorkspaceProjectEntity project(){var p=new WorkspaceProjectEntity();p.id="project";p.name="项目";p.background="固定背景";p.revision=1L;return p;}
    /** 文档目标与范围独立于 PRD 正文。 */
    private RequirementVersionEntity version(){var v=new RequirementVersionEntity();v.id="version";v.projectId="project";v.goal="交付目标";v.content="交付范围";v.revision=1L;return v;}
    /** 已保存状态没有读取初始化副作用。 */
    private LifecycleStateEntity state(Prd prd){var s=new LifecycleStateEntity();s.revision=1L;s.prdRevision=1L;s.directPrd=true;s.prdJson=json.encode(prd);return s;}
}
