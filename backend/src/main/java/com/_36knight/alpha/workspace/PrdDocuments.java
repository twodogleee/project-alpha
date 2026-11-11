package com._36knight.alpha.workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import static com._36knight.alpha.workspace.LifecycleContracts.Prd;

/** PRD 模板、结构校验及定点合并；旧版正文与不可变历史保持可读。@author Andersen */
public final class PrdDocuments {
    /** 不允许实例化纯转换工具。 */ private PrdDocuments() { }
    /** 旧版通用正文名称被冻结在同一目录，键保持兼容。 */
    public static final Map<String,String> TITLES=PrdTemplates.titles(PrdTemplates.catalog().legacyGlobalSections());
    /** 首版管理模板的默认整体说明；动态文档使用自身绑定版本。 */
    public static final Map<String,String> GLOBAL_TITLES=PrdTemplates.titles(PrdTemplates.resolve(PrdTemplates.LEGACY_ID,PrdTemplates.LEGACY_REVISION).globalSections());
    /** 稳定模块键由统一目录定义，不随显示名称改变。 */
    public static final Map<String,String> MODULE_TITLES=PrdTemplates.titles(PrdTemplates.resolve(PrdTemplates.LEGACY_ID,PrdTemplates.LEGACY_REVISION).moduleSections());
    /** 稳定页面键由统一目录定义，正文与待确认关联均使用该键。 */
    public static final Map<String,String> PAGE_TITLES=PrdTemplates.titles(PrdTemplates.resolve(PrdTemplates.LEGACY_ID,PrdTemplates.LEGACY_REVISION).pageSections());
    /** 文本缺省为空；结构和标识依然必须通过保存校验。 */
    private static String value(Map<String,String> sections,String key){return sections.getOrDefault(key,"");}
    /** 模块正文的稳定路径。 */
    public static String moduleKey(String module,String section){return "module:"+module+":"+section;}
    /** 页面正文的稳定路径，名称变化不改变引用。 */
    public static String pageKey(String module,String page,String section){return "page:"+module+":"+page+":"+section;}
    /** 展平当前文档中存在的正文位置，模型只能写这些位置。 */
    public static Map<String,String> sections(Prd p) {
        var result=new LinkedHashMap<String,String>();
        var texts=List.of(p.background(),p.objectives(),p.scope(),p.workflows(),p.rules(),p.exceptions(),p.acceptance(),p.nonfunctional(),p.exclusions());
        int index=0;for(String key:TITLES.keySet())result.put(key,texts.get(index++));
        for(var module:p.modules()) {
            MODULE_TITLES.keySet().forEach(key->result.put(moduleKey(module.id(),key),value(module.sections(),key)));
            for(var page:module.pages())PAGE_TITLES.keySet().forEach(key->result.put(pageKey(module.id(),page.id(),key),value(page.sections(),key)));
        }
        return result;
    }
    /** 目录标题包含模块和页面上下文，供会话焦点、差异和建议定位使用。 */
    public static Map<String,String> labels(Prd p) { return labels(p,PrdTemplates.forDocument(p)); }
    /** 动态模板由应用服务精确解析后传入，纯投影不访问数据库。 */
    public static Map<String,String> labels(Prd p,PrdTemplates.Definition template) {
        requireBinding(p,template);
        var result=new LinkedHashMap<>(usesLegacyHeadings(p)?TITLES:PrdTemplates.titles(template.globalSections()));
        for(var module:p.modules()) {
            String base="module:"+module.id();result.put(base,module.title());
            PrdTemplates.titles(template.moduleSections()).forEach((key,title)->result.put(base+":"+key,module.title()+" / "+title));
            for(var page:module.pages()) {
                String path="page:"+module.id()+":"+page.id();result.put(path,module.title()+" / "+page.title());
                PrdTemplates.titles(template.pageSections()).forEach((key,title)->result.put(path+":"+key,module.title()+" / "+page.title()+" / "+title));
            }
        }
        return result;
    }
    /** 模型请求必须定位到当前存在的文档范围。 */
    public static boolean targetExists(Prd p,String target){return targetExists(p,target,PrdTemplates.forDocument(p));}
    /** 发布模板下的定位沿用稳定路径，标题变化不影响目标有效性。 */
    public static boolean targetExists(Prd p,String target,PrdTemplates.Definition template){return "DOCUMENT".equals(target)||labels(p,template).containsKey(target);}
    /** 仅升级当前草稿的结构版本，不改动既有正文或历史来源。 */
    public static Prd withModules(Prd p,List<PrdStructure.Module> modules) {
        return copy(p,sections(p),p.unresolvedIssues(),2,modules);
    }
    /** 未涉及正文和待确认事项原样保留。 */
    public static Prd patch(Prd original,Map<String,String> changes,List<String> issues) {
        var values=sections(original);
        if(changes!=null) {
            if(!values.keySet().containsAll(changes.keySet())||changes.values().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("未知 PRD 章节或空值");
            values.putAll(changes);
        }
        var modules=new ArrayList<PrdStructure.Module>();
        for(var module:original.modules()) {
            var moduleSections=new LinkedHashMap<>(module.sections());
            MODULE_TITLES.keySet().forEach(key->{if(changes!=null&&changes.containsKey(moduleKey(module.id(),key)))moduleSections.put(key,values.get(moduleKey(module.id(),key)));});
            var pages=new ArrayList<PrdStructure.Page>();
            for(var page:module.pages()) {
                var pageSections=new LinkedHashMap<>(page.sections());
                PAGE_TITLES.keySet().forEach(key->{if(changes!=null&&changes.containsKey(pageKey(module.id(),page.id(),key)))pageSections.put(key,values.get(pageKey(module.id(),page.id(),key)));});
                pages.add(new PrdStructure.Page(page.id(),page.title(),page.type(),pageSections));
            }
            modules.add(new PrdStructure.Module(module.id(),module.title(),moduleSections,pages));
        }
        return copy(original,values,issues==null?original.unresolvedIssues():issues,original.templateVersion(),modules);
    }
    /** 先按稳定 ID 增改结构，再定点写正文；未列出的同级节点不删除。 */
    public static Prd apply(Prd original,WorkbenchContracts.Reply reply) {return apply(original,reply,PrdTemplates.forDocument(original));}
    /** 按任务绑定的发布模板验证结构，进行中的会话不会跟随新发布版本漂移。 */
    public static Prd apply(Prd original,WorkbenchContracts.Reply reply,PrdTemplates.Definition template) {
        requireBinding(original,template);
        var modules=new LinkedHashMap<String,PrdStructure.Module>();original.modules().forEach(m->modules.put(m.id(),m));
        if(reply.modules()!=null) {
            var seen=new HashSet<String>();
            for(var change:reply.modules()) {
                if(!seen.add(change.id()))throw new IllegalArgumentException("模块编辑标识重复");
                var old=modules.get(change.id());
                if(change.remove()) {
                    if(old==null||change.title()!=null||change.pages()!=null)throw new IllegalArgumentException("模块删除格式无效");
                    modules.remove(change.id());continue;
                }
                String title=change.title()==null?(old==null?null:old.title()):change.title();
                if(title==null||title.isBlank())throw new IllegalArgumentException("新模块必须有名称");
                var pages=new LinkedHashMap<String,PrdStructure.Page>();if(old!=null)old.pages().forEach(p->pages.put(p.id(),p));
                if(change.pages()!=null) {
                    var pageIds=new HashSet<String>();
                    for(var item:change.pages()) {
                        if(!pageIds.add(item.id()))throw new IllegalArgumentException("页面编辑标识重复");
                        var prior=pages.get(item.id());
                        if(item.remove()) {
                            if(prior==null||item.title()!=null||item.type()!=null)throw new IllegalArgumentException("页面删除格式无效");
                            pages.remove(item.id());continue;
                        }
                        String name=item.title()==null?(prior==null?null:prior.title()):item.title();
                        String type=item.type()==null?(prior==null?null:prior.type()):item.type();
                        if(name==null||name.isBlank()||type==null)throw new IllegalArgumentException("新页面必须有名称和类型");
                        pages.put(item.id(),new PrdStructure.Page(item.id(),name,type,prior==null?Map.of():prior.sections()));
                    }
                }
                modules.put(change.id(),new PrdStructure.Module(change.id(),title,old==null?Map.of():old.sections(),List.copyOf(pages.values())));
            }
        }
        Prd structured=reply.modules()==null||reply.modules().isEmpty()?original:withModules(original,List.copyOf(modules.values()));
        var result=patch(structured,reply.sections(),reply.unresolvedIssues());
        if(reply.issueLinks()!=null) {
            var links=new LinkedHashMap<>(result.issueLinks());links.putAll(reply.issueLinks());
            result=withIssueLinks(result,links);
        }
        validateStructure(result,template);return result;
    }
    /** 聚合重建保留历史需求来源，模板版本不可通过正文补丁改变。 */
    private static Prd copy(Prd p,Map<String,String> v,List<String> issues,int version,List<PrdStructure.Module> modules) {
        var result=new Prd(v.get("background"),v.get("objectives"),v.get("scope"),v.get("workflows"),v.get("rules"),v.get("exceptions"),v.get("acceptance"),v.get("nonfunctional"),v.get("exclusions"),p.requirementIds(),issues,version,modules,Map.of(),p.templateId(),p.templateRevision());
        var valid=sections(result).keySet();var links=new LinkedHashMap<String,List<String>>();
        p.issueLinks().forEach((issue,paths)->{if(issues.contains(issue))links.put(issue,paths.stream().filter(valid::contains).toList());});
        return withIssueLinks(result,links);
    }
    /** 问题原文作为关联键，目标使用稳定章节路径；未知目标由统一校验拒绝。 */
    public static Prd withIssueLinks(Prd p,Map<String,List<String>> links) {
        return new Prd(p.background(),p.objectives(),p.scope(),p.workflows(),p.rules(),p.exceptions(),p.acceptance(),p.nonfunctional(),p.exclusions(),p.requirementIds(),p.unresolvedIssues(),p.templateVersion(),p.modules(),links,p.templateId(),p.templateRevision());
    }
    /** 拒绝重复标识、未知章节及旧格式夹带模块；不推断业务文本是否正确。 */
    public static void validateStructure(Prd p) {validateStructure(p,PrdTemplates.forDocument(p));}
    /** 业务层只能传入已发布的精确版本，固定标准键不受排序和标题影响。 */
    public static void validateStructure(Prd p,PrdTemplates.Definition template) {
        requireBinding(p,template);
        var targets=sections(p).keySet();
        p.issueLinks().forEach((issue,paths)->{if(!p.unresolvedIssues().contains(issue)||!targets.containsAll(paths))throw new IllegalArgumentException("待确认事项必须关联现有问题与正文章节");});
        if(p.templateVersion()==1&&!p.modules().isEmpty())throw new IllegalArgumentException("模块文档必须使用新版模板");
        var ids=new HashSet<String>();
        for(var module:p.modules()) {
            if(!ids.add(module.id())||!MODULE_TITLES.keySet().containsAll(module.sections().keySet()))throw new IllegalArgumentException("模块标识重复或章节无效");
            var pages=new HashSet<String>();
            for(var page:module.pages()) {
                if(!pages.add(page.id())||!PAGE_TITLES.keySet().containsAll(page.sections().keySet()))throw new IllegalArgumentException("页面标识重复或章节无效");
                template.requiredPageSections(page.type());
            }
        }
    }
    /** 根据页面类型指定必要内容；不适用的规则应明确说明，不强填列表结构。 */
    public static List<String> requiredPageSections(String type) {
        return PrdTemplates.resolve(PrdTemplates.LEGACY_ID,PrdTemplates.LEGACY_REVISION).requiredPageSections(type);
    }
    /** 切换只改变模板引用；正文、稳定节点、历史需求来源和问题关联均保留。 */
    public static Prd withTemplate(Prd p,String id,int revision) {
        return withTemplate(p,PrdTemplates.resolve(id,revision));
    }
    /** 已解析发布定义只改变引用，保存时服务会再次验证版本确实已发布。 */
    public static Prd withTemplate(Prd p,PrdTemplates.Definition template) {
        return new Prd(p.background(),p.objectives(),p.scope(),p.workflows(),p.rules(),p.exceptions(),p.acceptance(),
                p.nonfunctional(),p.exclusions(),p.requirementIds(),p.unresolvedIssues(),p.templateVersion(),p.modules(),
                p.issueLinks(),template.id(),template.revision());
    }
    /** 只有未切换模板的旧九章节文档保持原名称和完整性门槛。 */
    private static boolean usesLegacyHeadings(Prd p) {
        return p.templateVersion()==1&&PrdTemplates.LEGACY_ID.equals(p.templateId())&&p.templateRevision()==PrdTemplates.LEGACY_REVISION;
    }
    /** 定稿检查模块页面内容和适用章节；草稿仍可保存不完整内容。 */
    public static List<String> blockers(Prd p) {return blockers(p,PrdTemplates.forDocument(p));}
    /** 根据文档绑定的已发布版本执行完整性检查，而不是当前草稿或最新模板。 */
    public static List<String> blockers(Prd p,PrdTemplates.Definition template) {
        var result=new ArrayList<String>();var values=sections(p);var labels=labels(p,template);
        var global=usesLegacyHeadings(p)?TITLES.keySet():template.requiredGlobalSections();
        for(String key:global)if(values.get(key).isBlank())result.add("补充"+labels.get(key));
        if(p.templateVersion()==2&&p.modules().isEmpty())result.add("至少补充一个功能模块及其页面");
        for(var module:p.modules()) {
            for(String key:template.requiredModuleSections())if(value(module.sections(),key).isBlank())result.add("补充"+labels.get(moduleKey(module.id(),key)));
            if(module.pages().isEmpty())result.add("补充"+module.title()+"的页面");
            for(var page:module.pages())for(String key:template.requiredPageSections(page.type()))if(value(page.sections(),key).isBlank())result.add("补充"+labels.get(pageKey(module.id(),page.id(),key)));
        }
        return result;
    }
    /** 新增、删除、改名及正文差异都进入任务结果；标题使用修改后名称，删除用旧名称。 */
    public static List<String> changedSections(Prd before,Prd after) {return changedSections(before,after,PrdTemplates.forDocument(before),PrdTemplates.forDocument(after));}
    /** 差异显示也使用各自绑定定义，不将模板改名误认为正文内容变化。 */
    public static List<String> changedSections(Prd before,Prd after,PrdTemplates.Definition beforeTemplate,PrdTemplates.Definition afterTemplate) {
        var old=sections(before);var next=sections(after);var keys=new java.util.LinkedHashSet<>(old.keySet());keys.addAll(next.keySet());
        var changed=new ArrayList<String>();for(String key:keys)if(!Objects.equals(old.get(key),next.get(key)))changed.add(key);
        var names=labels(before,beforeTemplate);var newNames=labels(after,afterTemplate);
        newNames.forEach((key,title)->{if(!key.matches(".*:(overview|rules|permissions|acceptance|fields|search|sorting|actions|form|interactions|states)$")&&!Objects.equals(names.get(key),title)&&!changed.contains(key))changed.add(key);});
        if(!before.modules().equals(after.modules())&&changed.isEmpty())changed.add("modules");
        if(!before.unresolvedIssues().equals(after.unresolvedIssues())||!before.issueLinks().equals(after.issueLinks()))changed.add("issues");
        if(!Objects.equals(before.templateId(),after.templateId())||before.templateRevision()!=after.templateRevision())changed.add("template");
        return changed;
    }
    /** 防止调用方误传其他版本导致检查规则与文档绑定不一致。 */
    private static void requireBinding(Prd prd,PrdTemplates.Definition template){
        if(!Objects.equals(prd.templateId(),template.id())||prd.templateRevision()!=template.revision())throw new IllegalArgumentException("PRD 模板定义与文档绑定不一致");
    }

}
