package com._36knight.alpha.template;

import com._36knight.alpha.project.ProjectException;
import com._36knight.alpha.template.persistence.PrdTemplateEntity;
import com._36knight.alpha.template.persistence.PrdTemplateVersionEntity;
import com._36knight.alpha.workspace.LifecycleContracts.Prd;
import com._36knight.alpha.workspace.PrdTemplates;
import com._36knight.alpha.workspace.PrdTemplates.Catalog;
import com._36knight.alpha.workspace.PrdTemplates.Definition;
import com._36knight.alpha.workspace.PrdTemplates.Section;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static com._36knight.alpha.template.PrdTemplateContracts.*;

/** 内置只读、自定义草稿可编辑、发布版本不可变；每次精确读取持久化定义，无运行期全局缓存。@author Andersen */
@Service
public class PrdTemplateService {
    /** 标准结构与旧版兼容要求从冻结的首版管理模板取得。 */
    private static final Definition STANDARD=PrdTemplates.resolve(PrdTemplates.LEGACY_ID,PrdTemplates.LEGACY_REVISION);
    /** 整体说明的前四项保持一个展示分组，组内允许重排。 */
    private static final Set<String> OVERVIEW=Set.of("background","objectives","scope","workflows");
    /** 模块至少交代用途和验收，不允许通过模板取消所有内容要求。 */
    private static final Set<String> MODULE_MINIMUM=Set.of("overview","acceptance");
    /** 页面基础状态和验收仍是完整性底线。 */
    private static final Set<String> PAGE_MINIMUM=Set.of("overview","states","acceptance");
    /** 草稿聚合及不可变发布记录。 */ private final PrdTemplateRepository repository;
    /** 定义 JSON 编解码，仅存普通文本。 */ private final ObjectMapper json;
    /** 非 HTTP 调用也执行有界输入校验。 */ private final Validator validator;
    /** 复制并发冲突需在回滚后另开事务读取幂等结果。 */ private final TransactionTemplate transactions;
    /** 注入存储和事务边界。 */
    public PrdTemplateService(PrdTemplateRepository repository,ObjectMapper json,Validator validator,PlatformTransactionManager manager){
        this.repository=repository;this.json=json;this.validator=validator;transactions=new TransactionTemplate(manager);
    }
    /** 公开目录只含已发布定义，保留所有历史版本以支持旧文档和进行中的任务。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Catalog catalog(){
        var definitions=new ArrayList<>(PrdTemplates.catalog().templates());
        repository.versions().forEach(item->definitions.add(decode(item.definitionJson)));
        var deleted=repository.templates().stream().filter(item->item.deleted).map(item->item.id).toList();
        return new Catalog(PrdTemplates.catalog().legacyGlobalSections(),definitions,deleted);
    }
    /** 管理视图包含未发布草稿，但不把草稿混进 PRD 可选目录。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Managed> management(){return repository.templates().stream().map(this::view).toList();}
    /** 只解析指定已发布版本，绝不回落最新版本或未发布草稿。 */
    public Definition resolve(String id,int revision){
        var builtin=PrdTemplates.catalog().templates().stream().filter(item->item.id().equals(id)&&item.revision()==revision).findFirst();
        if(builtin.isPresent())return builtin.get();
        var published=repository.version(id,revision);
        if(published==null)throw invalid("模板版本未发布或不存在，请刷新模板目录后重试");
        return decode(published.definitionJson);
    }
    /** 所有 PRD 业务路径按自身绑定解析，模板升级不改变文档规则。 */
    public Definition forDocument(Prd prd){return resolve(prd.templateId(),prd.templateRevision());}
    /** 新选用必须与删除在同一模板锁下串行；历史解析不调用此限制。 */
    @Transactional
    public void assertSelectable(String id,int revision){
        if(PrdTemplates.catalog().templates().stream().noneMatch(item->item.id().equals(id)))active(lock(id));
        resolve(id,revision);
    }
    /** 既有文档保持相同版本可以继续编辑；首次绑定、切换模板及升级版本拒绝已删除目标。 */
    @Transactional
    public void validateBinding(Prd previous,Prd next){
        if(previous==null||!Objects.equals(previous.templateId(),next.templateId())||previous.templateRevision()!=next.templateRevision())
            assertSelectable(next.templateId(),next.templateRevision());
    }
    /** 复制明确来源版本；重复请求返回原聚合，不重复创建。 */
    public Managed copy(Copy command){
        validate(command);
        try{return transactions.execute(status->{
            var previous=repository.copied(command.requestId());
            if(previous!=null)return copied(previous,command);
            assertSelectable(command.sourceTemplateId(),command.sourceTemplateRevision());
            Definition source=resolve(command.sourceTemplateId(),command.sourceTemplateRevision());
            var item=new PrdTemplateEntity();item.id="custom_"+UUID.randomUUID();item.revision=1;item.latestPublishedRevision=0;
            var config=configuration(source,command.name().trim());validateConfiguration(config);
            item.draftJson=encode(definition(item.id,1,source.guidance(),config));item.copyRequestId=command.requestId();
            item.copyCommandJson=encode(command);item.createdAt=now();item.updatedAt=item.createdAt;repository.insert(item);return view(item);
        });}catch(DuplicateKeyException exception){
            // 并发重试先结束失败事务，避免 PostgreSQL 回滚状态下继续查询。
            return transactions.execute(status->{var prior=repository.copied(command.requestId());if(prior==null)throw conflict("复制模板发生冲突，请重试");return copied(prior,command);});
        }
    }
    /** 已发布自定义模板显式打开下一草稿，原版本始终保留。 */
    @Transactional
    public Managed openDraft(String id,OpenDraft command){
        validate(command);var item=lock(id);checkRevision(item,command.expectedRevision());active(item);
        if(item.draftJson!=null)return view(item);
        var source=resolve(id,item.latestPublishedRevision);
        item.draftJson=encode(definition(id,item.latestPublishedRevision+1,source.guidance(),configuration(source,source.name())));
        advance(item);return view(item);
    }
    /** 保存仅改变当前草稿，不能替换任何已发布定义。 */
    @Transactional
    public Managed save(String id,Save command){
        validate(command);validateConfiguration(command.configuration());var item=lock(id);checkRevision(item,command.expectedRevision());active(item);
        if(item.draftJson==null)throw conflict("请先创建下一版本草稿");
        var before=decode(item.draftJson);var next=definition(id,before.revision(),before.guidance(),command.configuration());
        if(before.equals(next))return view(item);
        item.draftJson=encode(next);advance(item);return view(item);
    }
    /** 发布保存过的草稿；旧请求重试不能把后来创建的新草稿发布。 */
    @Transactional
    public Managed publish(String id,Publish command){
        validate(command);var item=lock(id);active(item);var previous=repository.published(id,command.requestId());
        if(previous!=null){
            if(previous.sourceRevision!=command.expectedRevision())throw conflict("发布请求标识已用于其他草稿修订");
            return view(item);
        }
        checkRevision(item,command.expectedRevision());
        if(item.draftJson==null)throw conflict("没有可发布的模板草稿");
        var draft=decode(item.draftJson);validateConfiguration(configuration(draft,draft.name()));
        if(draft.revision()!=item.latestPublishedRevision+1)throw conflict("模板草稿版本已失效，请刷新");
        var version=new PrdTemplateVersionEntity();version.id=UUID.randomUUID().toString();version.templateId=id;version.revision=draft.revision();
        version.definitionJson=item.draftJson;version.publishRequestId=command.requestId();version.sourceRevision=command.expectedRevision();version.createdAt=now();
        repository.publish(version);item.latestPublishedRevision=draft.revision();item.draftJson=null;advance(item);return view(item);
    }
    /** 软删除不清空草稿或发布版本；相同状态且当前修订不重复推进，过期状态命令拒绝。 */
    @Transactional
    public Managed deletion(String id,Deletion command){
        validate(command);var item=lock(id);checkRevision(item,command.expectedRevision());
        if(item.deleted==command.deleted())return view(item);
        item.deleted=command.deleted();advance(item);return view(item);
    }
    /** 配置仅可重命名、写提示、排序和调整非基础必填项，所有标准键必须完整保留。 */
    private void validateConfiguration(Configuration config){
        validate(config);
        checkSections(config.globalSections(),STANDARD.globalSections());checkSections(config.moduleSections(),STANDARD.moduleSections());checkSections(config.pageSections(),STANDARD.pageSections());
        if(!new HashSet<>(config.globalSections().subList(0,4).stream().map(Section::key).toList()).equals(OVERVIEW))throw invalid("整体说明与全局约定只能各自在分组内调整顺序");
        requireKeys(config.requiredGlobalSections(),keys(config.globalSections()),OVERVIEW,"整体说明");
        requireKeys(config.requiredModuleSections(),keys(config.moduleSections()),MODULE_MINIMUM,"模块");
        var expectedTypes=STANDARD.pageTypes().stream().map(PrdTemplates.PageType::key).toList();
        var types=config.pageTypes().stream().map(PrdTemplates.PageType::key).toList();
        if(types.size()!=new HashSet<>(types).size()||!new HashSet<>(types).equals(new HashSet<>(expectedTypes)))throw invalid("必须完整保留全部标准页面类型");
        if(!types.contains(config.defaultPageType()))throw invalid("默认页面类型不在标准类型中");
        for(var page:config.pageTypes()){
            String title=STANDARD.pageTypes().stream().filter(item->item.key().equals(page.key())).findFirst().orElseThrow().title();
            if(!Objects.equals(title,page.title()))throw invalid("标准页面类型名称不可修改");
            requireKeys(page.requiredSections(),keys(config.pageSections()),PAGE_MINIMUM,"页面");
        }
    }
    /** 标准章节不可删减、重复或插入未知键。 */
    private void checkSections(List<Section> actual,List<Section> standard){
        var keys=actual.stream().map(Section::key).toList();
        if(keys.size()!=new HashSet<>(keys).size()||!new HashSet<>(keys).equals(keys(standard)))throw invalid("必须完整保留标准章节，不可添加、删除或重复章节键");
    }
    /** 提取稳定键集合，不依赖显示顺序。 */
    private Set<String> keys(List<Section> sections){return new HashSet<>(sections.stream().map(Section::key).toList());}
    /** 必填项必须存在、不重复，且保留基础底线。 */
    private void requireKeys(List<String> required,Set<String> allowed,Set<String> minimum,String label){
        if(required==null||required.size()!=new HashSet<>(required).size()||!allowed.containsAll(required)||!new HashSet<>(required).containsAll(minimum))throw invalid(label+"必填项无效，必须保留基础必填章节");
    }
    /** 生成固定身份和版本的定义，写作总指导只继承已发布来源。 */
    private Definition definition(String id,int revision,String guidance,Configuration c){
        return new Definition(id,revision,c.name().trim(),c.description(),guidance,c.defaultPageType(),c.globalSections(),c.moduleSections(),c.pageSections(),c.pageTypes(),c.requiredGlobalSections(),c.requiredModuleSections());
    }
    /** 已发布定义投影为允许编辑的字段，不暴露写入 guidance 的入口。 */
    private Configuration configuration(Definition d,String name){
        return new Configuration(name,d.description(),d.defaultPageType(),d.globalSections(),d.moduleSections(),d.pageSections(),d.pageTypes(),d.requiredGlobalSections(),d.requiredModuleSections());
    }
    /** 加锁前拒绝内置模板写入，再读取自定义聚合。 */
    private PrdTemplateEntity lock(String id){
        if(PrdTemplates.catalog().templates().stream().anyMatch(item->item.id().equals(id)))throw invalid("内置模板只读，请先复制为自定义模板");
        var item=repository.lock(id);if(item==null)throw new ProjectException(HttpStatus.NOT_FOUND,"PRD_TEMPLATE_NOT_FOUND","自定义模板不存在");return item;
    }
    /** 对比复制命令避免幂等键跨意图复用，返回当前管理状态不覆写后续草稿。 */
    private Managed copied(PrdTemplateEntity item,Copy command){
        try{
            if(!Objects.equals(json.readValue(item.copyCommandJson,Copy.class),command))throw conflict("复制请求标识已用于其他来源或名称");
            return view(item);
        }catch(JsonProcessingException exception){throw new IllegalStateException("模板复制命令数据无效",exception);}
    }
    /** 并发编辑不自动覆盖，提示客户端保留输入并刷新。 */
    private void checkRevision(PrdTemplateEntity item,long expected){if(item.revision!=expected)throw conflict("模板已被更新，请保留编辑内容并刷新后重试");}
    /** 已删除模板的定义仍可用于历史，但管理动作必须先明确恢复。 */
    private void active(PrdTemplateEntity item){if(item.deleted)throw new ProjectException(HttpStatus.CONFLICT,"PRD_TEMPLATE_DELETED","模板已删除，请先恢复后再使用");}
    /** 更新管理锁值及时间，不改变发布历史。 */
    private void advance(PrdTemplateEntity item){item.revision++;item.updatedAt=now();repository.update(item);}
    /** 返回管理视图，发布内容通过目录读取。 */
    private Managed view(PrdTemplateEntity item){return new Managed(item.id,item.revision,item.latestPublishedRevision,item.draftJson==null?null:decode(item.draftJson),item.createdAt,item.updatedAt,item.deleted);}
    /** 所有服务命令执行 Bean Validation，避免内部调用绕过边界。 */
    private void validate(Object command){if(command==null||!validator.validate(command).isEmpty())throw invalid("模板配置不符合输入要求，请检查必填项和文本长度");}
    /** 普通 JSON 持久化，不运行配置中的文本。 */
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException exception){throw new IllegalStateException("模板序列化失败",exception);}}
    /** 读取服务器保存的定义，损坏数据不回退到其他版本。 */
    private Definition decode(String value){try{return json.readValue(value,Definition.class);}catch(JsonProcessingException exception){throw new IllegalStateException("模板定义数据无效",exception);}}
    /** UTC 时间用于持久化，不依赖应用服务器时区。 */
    private OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    /** 统一输入错误，消息不携带数据库细节。 */
    private ProjectException invalid(String message){return new ProjectException(HttpStatus.BAD_REQUEST,"PRD_TEMPLATE_INVALID",message);}
    /** 统一并发及幂等冲突。 */
    private ProjectException conflict(String message){return new ProjectException(HttpStatus.CONFLICT,"PRD_TEMPLATE_CONFLICT",message);}
}
