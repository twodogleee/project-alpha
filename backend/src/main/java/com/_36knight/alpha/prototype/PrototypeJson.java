package com._36knight.alpha.prototype;

import com._36knight.alpha.project.ProjectException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 有界 JSON 与版本化规范摘要：对象键排序，数组和正文空白保持原语义。@author Andersen */
@Component
public class PrototypeJson {
    /** 单个业务快照的 UTF-8 上限，避免正文或清单无限增长。 */
    private static final int MAX_BYTES=2*1024*1024;
    /** 使用应用相同的时间和记录序列化配置。 */
    private final ObjectMapper json;
    /** 注入既有映射器。 */
    public PrototypeJson(ObjectMapper json){this.json=json;}
    /** 有界序列化，不以截断内容冒充完整快照。 */
    public String encode(Object value){try{
        String text=json.writeValueAsString(value);
        if(text.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid("原型快照超过 2 MiB");
        return text;
    }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("原型快照编码失败",e);}}
    /** 只解析受信业务库 JSON；损坏数据显式失败。 */
    public <T>T decode(String text,Class<T> type){try{return json.readValue(text,type);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("原型快照损坏",e);}}
    /** 内部树用于受限业务清单校验。 */
    public JsonNode tree(String text){return decode(text,JsonNode.class);}
    /** 值转树沿用同一有界编码规则。 */
    public JsonNode node(Object value){return tree(encode(value));}
    /** sha256-canonical-json-v1 不删除正文空白或重排业务数组。 */
    public String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(canonical(node(value))).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    /** 递归排序对象键，数组保持顺序。 */
    private JsonNode canonical(JsonNode value){
        if(value.isObject()) {ObjectNode result=json.createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);keys.forEach(key->result.set(key,canonical(value.get(key))));return result;}
        if(value.isArray()){var result=json.createArrayNode();value.forEach(item->result.add(canonical(item)));return result;}
        return value;
    }
    /** 输入过大按业务错误拒绝。 */
    private ProjectException invalid(String message){return new ProjectException(HttpStatus.BAD_REQUEST,"INVALID_INPUT",message);}
}
