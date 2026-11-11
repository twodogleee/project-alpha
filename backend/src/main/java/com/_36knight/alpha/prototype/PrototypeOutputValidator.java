package com._36knight.alpha.prototype;

import com._36knight.alpha.prototype.PrototypeContracts.PageRef;
import com._36knight.alpha.prototype.PrototypeWorkflow.Output;
import com._36knight.alpha.prototype.PrototypeWorkflow.SourceFile;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 解析不可信模型 JSON 并校验范围、文件及声明式用例；不执行源码或用例。@author Andersen */
@Component
public class PrototypeOutputValidator {
    /** 响应限额含 JSON 转义开销，生成文件另按实际 UTF-8 字节限制。 */
    private static final int RESPONSE_BYTES = 8 * 1024 * 1024;
    /** 单个源码文件的实际文本限额。 */
    private static final int FILE_BYTES = 256 * 1024;
    /** 全部源码实际文本合计上限。 */
    private static final int TOTAL_BYTES = 5 * 1024 * 1024;
    /** 路由仅由普通片段和命名参数组成，不接受协议、查询、编码或通配符。 */
    private static final Pattern ROUTE = Pattern.compile("/(?:[A-Za-z0-9_-]+|:[A-Za-z][A-Za-z0-9_]*)(?:/(?:[A-Za-z0-9_-]+|:[A-Za-z][A-Za-z0-9_]*))*");
    /** 浏览器用例使用稳定测试标识，禁止 CSS 与脚本表达式。 */
    private static final Pattern TEST_ID = Pattern.compile("[A-Za-z0-9_:-]{1,120}");
    /** 文件名限定可移植路径片段，不接受隐藏文件、空片段或宿主路径。 */
    private static final Pattern FILE_PATH = Pattern.compile("src/(?:features|mock)/generated/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+\\.(?:vue|js|json)");
    /** 严格解析器独立于业务库读取，拒绝重复键与尾随文本。 */
    private final ObjectMapper json;

    /** 保留既有模块并收紧不可信 JSON 解析规则。 */
    public PrototypeOutputValidator(ObjectMapper json) {
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 计划必须精确覆盖冻结范围，不能自行添加页面或遗漏已选页面。 */
    public JsonNode plan(String response, PrototypeContracts.Scope scope) {
        JsonNode plan = parse(response);
        fields(plan, Set.of("pages", "entities", "impact", "gaps"));
        JsonNode pages = plan.path("pages");
        if (!pages.isArray() || pages.isEmpty() || pages.size() > 10) throw invalid("计划 pages 必须包含 1 至 10 个页面");
        Set<PageRef> expected = new HashSet<>(scope.pageRefs());
        Set<PageRef> found = new HashSet<>();
        Set<String> routes = new HashSet<>();
        boolean menu = false;
        for (JsonNode page : pages) {
            fields(page, Set.of("moduleId", "pageId", "title", "path", "component", "menu", "testPath"));
            PageRef ref = new PageRef(text(page, "moduleId", 64), text(page, "pageId", 64));
            if (!expected.contains(ref) || !found.add(ref)) throw invalid("计划页面必须按 moduleId 与 pageId 精确且唯一地引用冻结范围");
            text(page, "title", 160);
            String path = route(text(page, "path", 200), true);
            if (!routes.add(path)) throw invalid("一期生成计划每个页面须提供独立路由，路由不得重复");
            String component = path(text(page, "component", 240));
            if (!component.startsWith("src/features/generated/") || !component.endsWith(".vue")) throw invalid("页面 component 必须引用生成区域的 Vue 文件");
            if (page.has("menu") && !page.get("menu").isBoolean()) throw invalid("menu 只能是布尔值");
            menu |= !page.has("menu") || page.get("menu").booleanValue();
            String testPath = page.has("testPath") ? route(text(page, "testPath", 200), false) : path;
            if (path.contains(":") && !page.has("testPath")) throw invalid("动态路由必须提供带固定模拟 ID 的 testPath");
            if (path.contains(":") && (!page.has("menu") || page.get("menu").booleanValue())) throw invalid("动态路由必须显式设置 menu:false，菜单使用静态入口");
            if (!matches(path, testPath)) throw invalid("testPath 必须匹配自身路由且不含参数占位符");
        }
        if (!found.equals(expected) || !menu) throw invalid("计划必须覆盖全部选定页面且至少包含一个菜单入口");
        for (String key : List.of("entities", "impact", "gaps")) {
            if (plan.has(key) && (!plan.get(key).isArray() || plan.get(key).size() > 100)) throw invalid("entities、impact 与 gaps 必须是有界数组");
        }
        if (bytes(plan.toString()) > 128 * 1024) throw invalid("生成计划超过 128 KiB");
        return plan;
    }

    /** 文件响应必须包含全量生成区内容和有限用例，不接受模型自报成功状态。 */
    public Output output(String response, JsonNode plan) {
        JsonNode result = parse(response);
        fields(result, Set.of("files", "cases", "changeSummary"));
        JsonNode fileNodes = result.path("files");
        if (!fileNodes.isArray() || fileNodes.isEmpty() || fileNodes.size() > 100) throw invalid("files 必须包含 1 至 100 个生成文件");
        List<SourceFile> files = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        long total = 0;
        for (JsonNode file : fileNodes) {
            fields(file, Set.of("path", "content"));
            String path = path(text(file, "path", 240));
            if (!paths.add(path.toLowerCase(java.util.Locale.ROOT))) throw invalid("文件路径不能重复或仅大小写不同");
            JsonNode content = file.get("content");
            if (content == null || !content.isTextual()) throw invalid("文件 content 必须为完整文本");
            int size = bytes(content.textValue());
            total += size;
            if (size > FILE_BYTES || total > TOTAL_BYTES) throw invalid("文件超过单文件 256 KiB 或总计 5 MiB 限额");
            files.add(new SourceFile(path, content.textValue()));
        }
        Set<String> exactPaths = new HashSet<>();
        files.forEach(file -> exactPaths.add(file.path()));
        for (JsonNode page : plan.path("pages")) {
            if (!exactPaths.contains(page.path("component").textValue())) throw invalid("计划 component 必须存在于本轮全量 files 中");
        }
        JsonNode cases = cases(result.path("cases"), plan);
        String summary = text(result, "changeSummary", 4000);
        return new Output(plan, List.copyOf(files), cases, summary);
    }

    /** 从完整 JSON 或单个 JSON 代码围栏读取；不抽取混合自然语言里的片段。 */
    private JsonNode parse(String response) {
        if (response == null || bytes(response) > RESPONSE_BYTES) throw invalid("模型响应为空或超过 8 MiB");
        String text = response.strip();
        if (text.startsWith("```json\n") && text.endsWith("```")) text = text.substring(8, text.length() - 3).strip();
        else if (text.startsWith("```\n") && text.endsWith("```")) text = text.substring(4, text.length() - 3).strip();
        try {
            JsonNode value = json.readTree(text);
            if (value == null || !value.isObject()) throw invalid("模型必须返回一个 JSON 对象");
            return value;
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid("模型必须返回合法 JSON，禁止重复键、尾随内容和截断源码");
        }
    }

    /** 平台只解释声明式步骤，限制路由、选择器、文本与操作总量。 */
    private JsonNode cases(JsonNode cases, JsonNode plan) {
        if (!cases.isArray() || cases.size() > 20) throw invalid("cases 必须是最多 20 项的声明式用例数组");
        Set<String> paths = new HashSet<>();
        plan.path("pages").forEach(page -> paths.add(page.path("testPath").asText(page.path("path").asText())));
        int count = 0;
        for (JsonNode test : cases) {
            fields(test, Set.of("name", "path", "steps"));
            text(test, "name", 160);
            if (!paths.contains(text(test, "path", 240))) throw invalid("用例 path 必须引用计划中明确声明的测试路由");
            JsonNode steps = test.path("steps");
            if (!steps.isArray() || steps.isEmpty() || steps.size() > 20) throw invalid("每项用例必须包含 1 至 20 个步骤");
            count += steps.size();
            if (count > 100) throw invalid("全部用例最多 100 个步骤");
            for (JsonNode step : steps) {
                fields(step, Set.of("action", "testId", "text", "value"));
                String action = text(step, "action", 20);
                if (!Set.of("click", "fill", "assertText", "assertVisible").contains(action)) throw invalid("用例只支持 click、fill、assertText、assertVisible");
                if (!"assertText".equals(action) || step.has("testId")) {
                    if (!TEST_ID.matcher(text(step, "testId", 120)).matches()) throw invalid("testId 必须是稳定字母数字标识，不能包含选择器或代码");
                }
                if ("assertText".equals(action)) text(step, "text", 2000);
                if ("fill".equals(action)) {
                    if (!step.path("value").isTextual() || step.path("value").textValue().length() > 2000) throw invalid("fill 的 value 必须是最多 2000 字符的文本，允许空字符串");
                }
                if (step.has("text") && !"assertText".equals(action)) throw invalid("只有 assertText 可以携带 text");
                if (step.has("value") && !"fill".equals(action)) throw invalid("只有 fill 可以携带 value");
            }
        }
        return cases;
    }

    /** 不允许隐含命令、任意脚本或拼接给 Worker 的额外配置。 */
    private void fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw invalid("生成契约中的对象类型无效");
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) throw invalid("模型输出包含契约未允许的字段");
    }

    /** 读取有限纯文本；不把数字与布尔值隐式转换成字符串。 */
    private String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max) throw invalid("字段 " + field + " 必须是非空有界文本");
        return value.textValue();
    }

    /** 可移植源码相对路径只允许写入生成区域；保留 routes.js 给平台组装。 */
    private String path(String value) {
        if (!FILE_PATH.matcher(value).matches() || value.equals("src/features/generated/routes.js")) throw invalid("文件只能位于生成目录的普通 .vue/.js/.json 路径，routes.js 由平台维护");
        return value;
    }

    /** 受限站内路由不接受外链、查询或 URL 编码。 */
    private String route(String value, boolean parameters) {
        if (!ROUTE.matcher(value).matches() || (!parameters && value.contains(":"))) throw invalid("路由必须是简单站内路径；测试路径不能含动态参数");
        return value;
    }

    /** 逐片段匹配命名参数，避免直接把模型路由编译成正则。 */
    private boolean matches(String route, String sample) {
        String[] expected = route.split("/");
        String[] actual = sample.split("/");
        if (expected.length != actual.length) return false;
        for (int index = 0; index < expected.length; index++) if (!expected[index].startsWith(":") && !expected[index].equals(actual[index])) return false;
        return true;
    }

    /** 上限按实际编码字节计数，中文不能绕过文件限额。 */
    private int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }

    /** 返回不含模型原文和底层异常的固定规则诊断。 */
    private PrototypeGenerationException invalid(String message) { return new PrototypeGenerationException(message); }
}
