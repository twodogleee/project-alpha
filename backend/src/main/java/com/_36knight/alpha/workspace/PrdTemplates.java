package com._36knight.alpha.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 内置 PRD 模板的唯一版本化定义；已发布条目只追加版本，禁止原位改写。@author Andersen */
public final class PrdTemplates {
    /** 旧文档永久绑定首版管理系统模板，不随新版本发布变化。 */
    public static final String LEGACY_ID = "management";
    /** 兼容缺少模板引用的旧草稿和历史版本。 */
    public static final int LEGACY_REVISION = 1;
    /** 随应用发布的不可变模板目录，前端与模型使用同一份定义。 */
    private static final Catalog CATALOG = load();
    /** 纯定义目录不可实例化。 */
    private PrdTemplates() { }

    /**
     * 标准章节使用稳定 key，名称和写作提示随模板版本固定。
     * @param key 正文与问题关联使用的稳定键
     * @param title 当前版本的显示名称
     * @param hint 写作维度，不作为业务事实自动写入正文
     */
    public record Section(@NotBlank @Size(max=64) String key, @NotBlank @Size(max=120) String title, @NotNull @Size(max=2000) String hint) { }
    /**
     * 页面类型决定必填章节；不会删除不再必填的正文或问题关联。
     * @param key 页面保存的稳定类型标识
     * @param title 类型显示名称
     * @param requiredSections 定稿时必须填写的页面章节键
     */
    public record PageType(@NotBlank @Size(max=40) String key, @NotBlank @Size(max=120) String title,
            @NotNull @Size(max=11) List<@NotBlank String> requiredSections) {
        /** 防止调用方更改已发布的章节要求。 */
        public PageType { if(requiredSections!=null)requiredSections = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(requiredSections)); }
    }
    /**
     * 有边界的模板配置，只描述现有文档结构、写作指导与完整性要求。
     * @param id 跨版本稳定的模板身份
     * @param revision 只追加、不可原位修改的发布版本
     * @param name 供选择和系统配置显示的名称
     * @param description 适用产品范围说明
     * @param guidance 模型写作引导，不能覆盖系统行为或充当业务事实
     * @param defaultPageType 新页面初始选项，不改变既有页面类型
     * @param globalSections 有序整体章节定义
     * @param moduleSections 有序模块章节定义
     * @param pageSections 有序页面章节定义
     * @param pageTypes 所有兼容页面类型及必填要求，顺序用于创建选项
     * @param requiredGlobalSections 整体说明的定稿必填键
     * @param requiredModuleSections 模块的定稿必填键
     */
    public record Definition(String id, int revision, String name, String description, String guidance,
            String defaultPageType, List<Section> globalSections, List<Section> moduleSections,
            List<Section> pageSections, List<PageType> pageTypes, List<String> requiredGlobalSections,
            List<String> requiredModuleSections) {
        /** 嵌套列表全部只读，历史解析不会被运行期配置修改。 */
        public Definition {
            globalSections = List.copyOf(globalSections); moduleSections = List.copyOf(moduleSections);
            pageSections = List.copyOf(pageSections); pageTypes = List.copyOf(pageTypes);
            requiredGlobalSections = List.copyOf(requiredGlobalSections); requiredModuleSections = List.copyOf(requiredModuleSections);
        }
        /** 查找当前模板对指定页面类型的完整性要求；未知类型直接拒绝。 */
        public List<String> requiredPageSections(String type) {
            return pageTypes.stream().filter(page -> page.key().equals(type)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("当前模板不支持页面类型：" + type)).requiredSections();
        }
    }
    /**
     * 旧版章节名称与所有已发布模板版本一同保留。
     * @param legacyGlobalSections 结构版本 1 的固定显示名称
     * @param templates 所有已发布版本，不能删除仍被文档引用的版本
     * @param deletedTemplateIds 已删除的自定义模板身份，仅限制新选择，不影响历史解析
     */
    public record Catalog(List<Section> legacyGlobalSections, List<Definition> templates, List<String> deletedTemplateIds) {
        /** 目录发布后不可原位修改。 */
        public Catalog {
            legacyGlobalSections = List.copyOf(legacyGlobalSections); templates = List.copyOf(templates);
            deletedTemplateIds = deletedTemplateIds==null?List.of():List.copyOf(deletedTemplateIds);
        }
        /** 兼容没有删除元数据的内置资源及原有目录构造。 */
        public Catalog(List<Section> legacyGlobalSections,List<Definition> templates){this(legacyGlobalSections,templates,List.of());}
    }
    /** 返回公开模板定义，不包含凭证或可执行表达式。 */
    public static Catalog catalog() { return CATALOG; }
    /** 精确读取文档绑定版本，未知版本拒绝而不是静默回落最新模板。 */
    public static Definition resolve(String id, int revision) {
        return CATALOG.templates().stream().filter(item -> item.id().equals(id) && item.revision() == revision)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("PRD 模板版本不存在，请刷新模板目录后重试"));
    }
    /** 文档所绑定的模板不会随目录新增版本自动升级。 */
    public static Definition forDocument(LifecycleContracts.Prd prd) { return resolve(prd.templateId(), prd.templateRevision()); }
    /** 统一产生按模板章节顺序排列的标题映射。 */
    public static Map<String, String> titles(List<Section> sections) {
        var result = new LinkedHashMap<String, String>(); sections.forEach(section -> result.put(section.key(), section.title()));
        return Collections.unmodifiableMap(result);
    }
    /** 从唯一发布资源读取并检查重复版本，启动失败不掩盖模板缺失。 */
    private static Catalog load() {
        try (var stream = PrdTemplates.class.getResourceAsStream("/prd-templates/catalog.json")) {
            if (stream == null) throw new IllegalStateException("PRD 模板资源不存在");
            Catalog catalog = new ObjectMapper().readValue(stream, Catalog.class);
            var versions = new java.util.HashSet<String>();
            for (var definition : catalog.templates()) {
                if (!versions.add(definition.id() + "@" + definition.revision())) throw new IllegalStateException("PRD 模板版本重复");
                var globals = titles(definition.globalSections()).keySet(); var modules = titles(definition.moduleSections()).keySet();
                var pages = titles(definition.pageSections()).keySet();
                if (!globals.containsAll(definition.requiredGlobalSections()) || !modules.containsAll(definition.requiredModuleSections()))
                    throw new IllegalStateException("PRD 模板必填章节不存在");
                for (var type : definition.pageTypes()) if (!pages.containsAll(type.requiredSections())) throw new IllegalStateException("PRD 页面必填章节不存在");
                definition.requiredPageSections(definition.defaultPageType());
            }
            return catalog;
        } catch (IOException exception) { throw new IllegalStateException("PRD 模板资源无效", exception); }
    }
}
