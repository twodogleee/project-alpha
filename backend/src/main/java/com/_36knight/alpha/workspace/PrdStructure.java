package com._36knight.alpha.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

/** 功能型 PRD 的模块、页面与结构编辑契约；正文使用纯文本或 Markdown 文本。@author Andersen */
public final class PrdStructure {
    /** 契约容器不能实例化。 */ private PrdStructure() { }
    /** 文档内稳定标识，不以可编辑名称或数组下标作为引用。 */
    public static final String ID = "[A-Za-z0-9_-]{1,64}";
    /** 模块汇总业务规则与权限，页面按列表顺序展示。 */
    public record Module(@NotBlank @Pattern(regexp=ID) String id, @NotBlank @Size(max=120) String title,
            @NotNull @Size(max=4) Map<String,@NotNull @Size(max=10000) String> sections,
            @NotNull @Size(max=30) List<@NotNull @Valid Page> pages) { }
    /** 页面正文按字段语义分栏；未填部分可缺省，定稿时另行检查。 */
    public record Page(@NotBlank @Pattern(regexp=ID) String id, @NotBlank @Size(max=120) String title,
            @NotBlank @Size(max=40) String type,
            @NotNull @Size(max=11) Map<String,@NotNull @Size(max=10000) String> sections) { }
    /** 只调整指定模块的名称和页面结构，未列出的模块与页面原样保留；删除需显式 remove。 */
    public record ModuleChange(@NotBlank @Pattern(regexp=ID) String id, @Size(min=1,max=120) String title,
            boolean remove, @Size(max=30) List<@NotNull @Valid PageChange> pages) { }
    /** 新页面必须给出名称和类型；已有页面可仅改名或改类型，正文从 sections 定点修改。 */
    public record PageChange(@NotBlank @Pattern(regexp=ID) String id, @Size(min=1,max=120) String title,
            @Size(min=1,max=40) String type, boolean remove) { }
}
