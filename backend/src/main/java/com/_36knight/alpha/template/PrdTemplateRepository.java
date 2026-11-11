package com._36knight.alpha.template;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com._36knight.alpha.template.mapper.PrdTemplateMapper;
import com._36knight.alpha.template.mapper.PrdTemplateVersionMapper;
import com._36knight.alpha.template.persistence.PrdTemplateEntity;
import com._36knight.alpha.template.persistence.PrdTemplateVersionEntity;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 维护模板草稿及只增不改的发布记录，不承担配置规则。@author Andersen */
@Repository
public class PrdTemplateRepository {
    /** 可编辑聚合根。 */ private final PrdTemplateMapper templates;
    /** 不可变发布历史。 */ private final PrdTemplateVersionMapper versions;
    /** 注入同一数据源的 Mapper。 */
    public PrdTemplateRepository(PrdTemplateMapper templates,PrdTemplateVersionMapper versions){this.templates=templates;this.versions=versions;}
    /** 管理目录按创建顺序稳定排列。 */
    public List<PrdTemplateEntity> templates(){return templates.selectList(new QueryWrapper<PrdTemplateEntity>().orderByAsc("created_at","id"));}
    /** 返回全部保留版本，供历史文档精确解析。 */
    public List<PrdTemplateVersionEntity> versions(){return versions.selectList(new QueryWrapper<PrdTemplateVersionEntity>().orderByAsc("template_id","revision"));}
    /** 查找聚合根，不存在返回空。 */
    public PrdTemplateEntity get(String id){return templates.selectById(id);}
    /** 事务内序列化同一模板的编辑和发布。 */
    public PrdTemplateEntity lock(String id){return templates.lock(id);}
    /** 查询复制幂等结果。 */
    public PrdTemplateEntity copied(String requestId){return templates.selectOne(new QueryWrapper<PrdTemplateEntity>().eq("copy_request_id",requestId));}
    /** 精确查找已发布版本，不访问未发布草稿。 */
    public PrdTemplateVersionEntity version(String id,int revision){return versions.selectOne(new QueryWrapper<PrdTemplateVersionEntity>().eq("template_id",id).eq("revision",revision));}
    /** 查询同一模板上的发布幂等结果。 */
    public PrdTemplateVersionEntity published(String id,String requestId){return versions.selectOne(new QueryWrapper<PrdTemplateVersionEntity>().eq("template_id",id).eq("publish_request_id",requestId));}
    /** 新建自定义草稿聚合。 */
    public void insert(PrdTemplateEntity item){templates.insert(item);}
    /** 更新草稿及锁值，不修改发布记录。 */
    public void update(PrdTemplateEntity item){templates.updateById(item);}
    /** 发布记录只允许插入，不提供更新或删除能力。 */
    public void publish(PrdTemplateVersionEntity version){versions.insert(version);}
}
