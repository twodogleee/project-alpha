package com._36knight.alpha.prototype;

import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.prototype.persistence.PrototypeSourceEntity;
import com._36knight.alpha.template.PrdTemplateService;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.LifecycleRepository;
import com._36knight.alpha.workspace.PrdDocuments;
import com._36knight.alpha.workspace.persistence.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import static com._36knight.alpha.prototype.PrototypeContracts.*;

/** 固定来源与保守的新鲜度判断；未知依赖返回待复核，不声称语义完整覆盖。@author Andersen */
@Component
public class PrototypeSources {
    /** 正式 PRD 基线只从业务库读取。 */ private final LifecycleRepository lifecycle;
    /** 写作模板固定定义也进入快照，不随默认模板升级。 */ private final PrdTemplateService templates;
    /** 有界规范化编码。 */ private final PrototypeJson json;
    /** 注入只读来源依赖。 */
    public PrototypeSources(LifecycleRepository lifecycle,PrdTemplateService templates,PrototypeJson json){this.lifecycle=lifecycle;this.templates=templates;this.json=json;}

    /** 在项目锁中冻结完整正文与上下文；本方法不写数据库。 */
    public PrototypeSourceEntity freeze(String prototypeId,WorkspaceProjectEntity project,RequirementVersionEntity version,
                                        LifecycleStateEntity state,Source source,Scope selection){
        Prd prd=json.decode(state.prdJson,Prd.class);
        if("BASELINE".equals(source.kind())){
            var baseline=lifecycle.baseline(source.id());
            if(baseline==null||!version.id.equals(baseline.versionId)||!"PRD".equals(baseline.kind)
                    ||!Objects.equals(source.id(),state.prdBaselineId)||!Objects.equals(baseline.sourceRevision,state.prdRevision)
                    ||!json.hash(json.tree(baseline.contentJson)).equals(json.hash(prd)))throw changed("PRD 定稿已失效或不属于该文档");
            prd=json.decode(baseline.contentJson,Prd.class);
        }else if(!"DRAFT".equals(source.kind())||source.id()!=null)throw invalid("新来源只能选择已保存草稿或有效定稿");
        PrdDocuments.validateStructure(prd,templates.forDocument(prd));
        var scope=scope(prd,selection);
        var context=context(project,version,prd);
        var row=new PrototypeSourceEntity();row.id=UUID.randomUUID().toString();row.prototypeId=prototypeId;row.versionId=version.id;
        row.sourceKind=source.kind();row.prdRevision=state.prdRevision;row.lifecycleRevision=state.revision;row.versionRevision=version.revision;
        row.prdBaselineId="BASELINE".equals(source.kind())?source.id():null;row.prdJson=json.encode(prd);row.contextJson=json.encode(context);row.scopeJson=json.encode(scope);
        row.fingerprintsJson=json.encode(fingerprints(prd,scope,context));
        row.contentSha256=json.hash(Map.of("algorithm","sha256-canonical-json-v1","prd",prd,"context",semanticContext(context),"scope",scope));
        row.createdAt=OffsetDateTime.now(ZoneOffset.UTC);return row;
    }
    /** 选定范围显式解析复合页面身份，不支持的类型或超量页面必须拒绝。 */
    public Scope scope(Prd prd,Scope selection){
        var pages=new LinkedHashMap<PageRef,String>();
        for(var module:prd.modules())for(var page:module.pages())pages.put(new PageRef(module.id(),page.id()),page.type());
        List<PageRef> chosen;
        if("ALL".equals(selection.mode())){
            if(!selection.pageRefs().isEmpty())throw invalid("ALL 范围不能同时指定页面子集");
            chosen=List.copyOf(pages.keySet());
        }else chosen=List.copyOf(selection.pageRefs());
        if(chosen.isEmpty()||chosen.size()>10||new HashSet<>(chosen).size()!=chosen.size())throw unsupported("生成范围需要 1 到 10 个不重复页面");
        for(var ref:chosen)if(!pages.containsKey(ref)||!Set.of("LIST","FORM","DETAIL").contains(pages.get(ref)))throw unsupported("范围含不存在或模板不支持的页面，请明确选择支持的子集");
        // 冻结后 ALL 同样携带完整范围，不能在以后读取时重新展开。
        return new Scope(selection.mode(),chosen);
    }
    /** 来源摘要供业务视图和执行适配器读取；不包含私有运行配置。 */
    public SourceView view(PrototypeSourceEntity row){return new SourceView(row.id,row.sourceKind,row.prdRevision,row.prdBaselineId,row.contentSha256,json.decode(row.scopeJson,Scope.class),json.decode(row.prdJson,Prd.class),row.createdAt);}
    /** 直接依赖改变为 STALE，未覆盖区域变化保守标记 REVIEW_REQUIRED。 */
    public String freshness(PrototypeSourceEntity source,WorkspaceProjectEntity project,RequirementVersionEntity version,LifecycleStateEntity state){
        if(state==null||state.prdJson==null)return "STALE";
        if("BASELINE".equals(source.sourceKind)&&!Objects.equals(source.prdBaselineId,state.prdBaselineId))return "STALE";
        try{
            Prd current=json.decode(state.prdJson,Prd.class);Scope selected=json.decode(source.scopeJson,Scope.class);
            scope(current,new Scope("SELECTED",selected.pageRefs()));
            JsonNode before=json.tree(source.fingerprintsJson),after=json.node(fingerprints(current,selected,context(project,version,current)));
            if(!before.path("algorithm").equals(after.path("algorithm")))return "REVIEW_REQUIRED";
            for(String key:List.of("global","context","modules","pages"))if(!before.path(key).equals(after.path(key)))return "STALE";
            return before.path("document").equals(after.path("document"))?"CURRENT":"REVIEW_REQUIRED";
        }catch(ProjectException|IllegalArgumentException e){return "STALE";}
    }
    /** 固定项目背景、文档范围和精确模板；不读取模型设置或凭证。 */
    private Map<String,Object> context(WorkspaceProjectEntity project,RequirementVersionEntity version,Prd prd){
        return Map.of("projectRevision",project.revision,"background",project.background,"goal",version.goal,"scope",version.content,"writingTemplate",templates.forDocument(prd));
    }
    /** 项目显示名称改动不改变输入语义；原修订仍在完整上下文中留存。 */
    private Map<String,Object> semanticContext(Map<String,Object> context){var result=new LinkedHashMap<>(context);result.remove("projectRevision");return result;}
    /** 所选页面依赖全局、所在模块规则、共享背景；其他变化不能静默当作无影响。 */
    private Map<String,Object> fingerprints(Prd prd,Scope scope,Map<String,Object> context){
        var global=json.node(prd).deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)global).remove("modules");
        var modules=new TreeMap<String,String>();var pages=new TreeMap<String,String>();
        for(var ref:scope.pageRefs()){
            var module=prd.modules().stream().filter(item->item.id().equals(ref.moduleId())).findFirst().orElseThrow();
            var page=module.pages().stream().filter(item->item.id().equals(ref.pageId())).findFirst().orElseThrow();
            modules.put(module.id(),json.hash(Map.of("title",module.title(),"sections",module.sections())));
            pages.put(ref.moduleId()+"/"+ref.pageId(),json.hash(page));
        }
        return Map.of("algorithm","sha256-canonical-json-v1","global",json.hash(global),"context",json.hash(semanticContext(context)),"modules",modules,"pages",pages,"document",json.hash(prd));
    }
    /** 来源已变化，不能隐式换成另一份正文。 */ private ProjectException changed(String message){return new ProjectException(HttpStatus.CONFLICT,"PROTOTYPE_SOURCE_CHANGED",message);}
    /** 不支持范围不自动降级或截断。 */ private ProjectException unsupported(String message){return new ProjectException(HttpStatus.BAD_REQUEST,"PROTOTYPE_UNSUPPORTED_SCOPE",message);}
    /** 原始输入格式错误。 */ private ProjectException invalid(String message){return new ProjectException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",message);}
}
