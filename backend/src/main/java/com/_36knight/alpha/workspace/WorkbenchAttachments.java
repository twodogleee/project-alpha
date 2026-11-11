package com._36knight.alpha.workspace;

import com._36knight.alpha.project.ProjectException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** 文本附件的服务端边界；不读取服务器路径、不执行文件内容。@author Andersen */
public final class WorkbenchAttachments {
    /** 无实例状态，附件随任务快照保存在业务数据库。 */
    private WorkbenchAttachments() { }
    /** 限制原文大小、格式和重名；拒绝二进制控制字符，不截断用户资料。 */
    public static void validate(List<WorkbenchContracts.Attachment> attachments) {
        int total=0;var names=new HashSet<String>();
        for(var attachment:attachments) {
            String name=attachment.name(),text=attachment.content(),lower=name.toLowerCase(Locale.ROOT);
            if(!(lower.endsWith(".md")||lower.endsWith(".txt"))||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl))throw invalid("附件仅支持 Markdown（.md）和 TXT，文件名不能包含路径");
            if(!names.add(name))throw invalid("同一轮不能添加同名附件");
            if(text.getBytes(StandardCharsets.UTF_8).length>262144||text.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw invalid("附件须为纯文本，每份不超过 256 KB");
            total+=text.length();
        }
        if(total>150000)throw invalid("本轮附件正文合计不能超过 15 万字符，请拆分资料");
    }
    /** 附件校验只返回业务原因，不回显参考正文。 */
    private static ProjectException invalid(String text) { return new ProjectException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",text); }
}
