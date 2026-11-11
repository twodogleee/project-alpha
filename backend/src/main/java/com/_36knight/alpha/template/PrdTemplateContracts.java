package com._36knight.alpha.template;

import com._36knight.alpha.workspace.PrdTemplates.Definition;
import com._36knight.alpha.workspace.PrdTemplates.PageType;
import com._36knight.alpha.workspace.PrdTemplates.Section;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;

/** 有边界的模板编辑契约；不接受自定义字段、执行表达式或系统提示词。@author Andersen */
public final class PrdTemplateContracts {
    /** 契约命名空间不实例化。 */ private PrdTemplateContracts(){ }
    /** 从明确已发布来源复制，requestId 在未知网络结果后重用以避免重复模板。 */
    public record Copy(@NotBlank @Size(max=80) String sourceTemplateId,@Min(1) int sourceTemplateRevision,
            @NotBlank @Size(max=120) String name,@NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId){ }
    /** 配置只影响呈现与写作要求；key、类型集合及基础完整性由服务校验。 */
    public record Configuration(@NotBlank @Size(max=120) String name,@NotNull @Size(max=2000) String description,
            @NotBlank @Size(max=40) String defaultPageType,
            @NotNull @Size(min=9,max=9) List<@NotNull @Valid Section> globalSections,
            @NotNull @Size(min=4,max=4) List<@NotNull @Valid Section> moduleSections,
            @NotNull @Size(min=11,max=11) List<@NotNull @Valid Section> pageSections,
            @NotNull @Size(min=11,max=11) List<@NotNull @Valid PageType> pageTypes,
            @NotNull @Size(max=9) List<@NotBlank String> requiredGlobalSections,
            @NotNull @Size(max=4) List<@NotBlank String> requiredModuleSections){ }
    /** 保存草稿不能自动发布，必须基于最新管理修订。 */
    public record Save(@Min(1) long expectedRevision,@NotNull @Valid Configuration configuration){ }
    /** 为已有自定义模板打开下一版草稿。 */
    public record OpenDraft(@Min(1) long expectedRevision){ }
    /** 发布的是已保存草稿，幂等键关联明确来源修订。 */
    public record Publish(@Min(1) long expectedRevision,@NotBlank @Pattern(regexp="[A-Za-z0-9-]{16,64}") String requestId){ }
    /** 软删除或恢复模板，保存草稿与发布历史；状态变更必须基于当前管理修订。 */
    public record Deletion(@Min(1) long expectedRevision,@NotNull Boolean deleted){ }
    /** 只读管理真值；draft.revision 为拟发布版本，revision 为并发锁，两者不可混用。 */
    public record Managed(String id,long revision,int latestPublishedRevision,Definition draft,OffsetDateTime createdAt,OffsetDateTime updatedAt,boolean deleted){ }
}
