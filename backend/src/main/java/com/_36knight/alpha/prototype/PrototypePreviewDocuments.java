package com._36knight.alpha.prototype;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;

/** 平台拥有的桥接和独立窗口 HTML；只注入已校验配置，不拼接模型正文或运行诊断。@author Andersen */
final class PrototypePreviewDocuments {
    /** 仅用于安全序列化固定配置，不接受客户端脚本。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 工具类型不承载可变运行状态。 */
    private PrototypePreviewDocuments() { }

    /** 注入平台同源外链桥接；生成页面拿不到可授权外域脚本的 nonce，CSP 只允许 self。 */
    static String frame(String html, String sessionId, String previewOrigin) {
        String bridge = "<script src=\"" + previewOrigin + "/bridge/" + sessionId + "/bridge.js\" crossorigin=\"anonymous\"></script>";
        int head = html.toLowerCase(java.util.Locale.ROOT).indexOf("<head>");
        return head < 0 ? bridge + html : html.substring(0, head + 6) + bridge + html.substring(head + 6);
    }

    /** 平台桥接单独作为受控 JS 资源提供，静态脚本和内嵌配置仍按服务器固定协议生成。 */
    static String bridge(String sessionId, String revisionId, String previewOrigin, Set<String> paths) {
        String config = scriptJson(Map.of("sessionId", sessionId, "revisionId", revisionId,
                "hostOrigin", previewOrigin, "paths", paths));
        return """
            (() => {
              // 所有上报身份来自平台会话；错误正文不读取模型或宿主异常。
              const config = CONFIG;
              const validPath = value => typeof value === 'string' && value.length <= 220 && new RegExp('^/[A-Za-z0-9_./-]*$').test(value) && !value.includes('..');
              const path = () => {
                const value = location.hash.slice(1) || '/';
                return validPath(value) ? value : '/';
              };
              const report = (type, detail = {}) => {
                if (window.parent === window) return;
                // 生成层只报告给独立服务上的平台 wrapper，绝不向任意嵌入方广播。
                window.parent.postMessage({ type, sessionId: config.sessionId, revisionId: config.revisionId, ...detail }, config.hostOrigin);
              };
              let ready = false;
              const navigated = () => { if (ready) report('NAVIGATED', { path: path() }); };
              for (const name of ['pushState', 'replaceState']) {
                const original = history[name];
                history[name] = function (...args) { const result = Reflect.apply(original, this, args); navigated(); return result; };
              }
              addEventListener('DOMContentLoaded', () => { ready = true; report('READY', { path: path() }); }, { once: true });
              addEventListener('hashchange', navigated);
              addEventListener('popstate', navigated);
              // 定位只接受确切父窗口、白名单 origin、当前修订和冻结页面路由。
              addEventListener('message', event => {
                const value = event.data;
                if (event.source !== window.parent || event.origin !== config.hostOrigin || !value || typeof value !== 'object' || Array.isArray(value)) return;
                if (Object.keys(value).some(key => !['type', 'sessionId', 'revisionId', 'path'].includes(key))) return;
                if (value.type !== 'SELECT_PAGE' || value.sessionId !== config.sessionId || value.revisionId !== config.revisionId || !validPath(value.path) || !config.paths.includes(value.path)) return;
                location.hash = value.path;
                report('SELECT_PAGE', { path: value.path });
              });
              addEventListener('error', () => report('ERROR', { message: '预览页面发生运行错误，请查看构建验证记录。' }));
              addEventListener('unhandledrejection', () => report('ERROR', { message: '预览页面发生异步错误，请查看构建验证记录。' }));
            })();
            """.replace("CONFIG", config);
    }

    /** 平台包装限制生成页面的自身导航；嵌入模式仅转发受限消息，独立窗口补充标题栏。 */
    static String wrapper(String frameUrl, String sessionId, String revisionId, String nonce, String parentOrigin,
            Set<String> paths, boolean embedded) {
        String config = scriptJson(Map.of("sessionId", sessionId, "revisionId", revisionId, "parentOrigin", parentOrigin, "paths", paths));
        return """
            <!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Alpha 原型预览</title><style nonce="NONCE">
              *{box-sizing:border-box}body{margin:0;color:#172033;background:#f1f5f9;font:14px system-ui,sans-serif}
              header{height:68px;background:white;padding:12px 22px;border-bottom:1px solid #dde3eb;display:flex;align-items:center;gap:28px}
              h1{font-size:16px;margin:0 0 4px}p{font-size:12px;color:#64748b;margin:0}#status{margin-left:auto;font-size:12px}
              iframe{display:block;width:100%;height:FRAME_HEIGHT;border:0;background:white}HEADER_STYLE
            </style></head><body><header><div><h1>原型预览</h1><p>业务动作使用模拟数据 · 临时预览到期后请回工作台重新打开</p></div><span id="status" role="status">正在加载预览</span></header>
            <iframe id="prototype-frame" title="管理端原型预览" src="FRAME" sandbox="allow-scripts" referrerpolicy="no-referrer"></iframe>
            <script nonce="NONCE">
              // 平台窗口仅接收其唯一 opaque 子窗口的受限消息，拒绝额外字段和伪造身份。
              const config = CONFIG;
              const frame = document.getElementById('prototype-frame');
              const status = document.getElementById('status');
              const accepts = value => {
                if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
                if (Object.keys(value).some(key => !['type', 'sessionId', 'revisionId', 'path', 'message'].includes(key))) return;
                if (!['READY', 'NAVIGATED', 'SELECT_PAGE', 'ERROR'].includes(value.type) || value.sessionId !== config.sessionId || value.revisionId !== config.revisionId) return;
                if (value.path !== undefined && (typeof value.path !== 'string' || value.path.length > 220 || !new RegExp('^/[A-Za-z0-9_./-]*$').test(value.path) || value.path.includes('..'))) return;
                if (value.message !== undefined && (typeof value.message !== 'string' || value.message.length > 300)) return;
                return value.type === 'ERROR' ? typeof value.message === 'string' && value.path === undefined : typeof value.path === 'string' && value.message === undefined;
              };
              addEventListener('message', event => {
                const value = event.data;
                if (!accepts(value)) return;
                // 页面定位只从确切 Alpha 父窗口进入，不能成为任意导航或脚本执行入口。
                if (window.parent !== window && event.source === window.parent && event.origin === config.parentOrigin) {
                  if (value.type === 'SELECT_PAGE' && config.paths.includes(value.path)) frame.contentWindow.postMessage(value, '*');
                  return;
                }
                if (event.source !== frame.contentWindow || event.origin !== 'null') return;
                status.textContent = value.type === 'ERROR' ? '预览页面发生运行错误，请返回工作台查看。' : '预览就绪' + (value.path ? ' · ' + value.path : '');
                if (window.parent !== window) window.parent.postMessage(value.type === 'ERROR' ? { type: 'ERROR', sessionId: config.sessionId, revisionId: config.revisionId, message: '预览页面发生运行错误，请查看构建验证记录。' } : value, config.parentOrigin);
              });
            </script></body></html>
            """.replace("FRAME_HEIGHT", embedded ? "100vh" : "calc(100vh - 68px)")
                .replace("HEADER_STYLE", embedded ? "header{display:none}" : "")
                .replace("NONCE", nonce).replace("FRAME", frameUrl).replace("CONFIG", config);
    }

    /** 防止 JSON 字符关闭 script 或引入 HTML/行分隔语义，包含修订身份也不能例外。 */
    private static String scriptJson(Object value) {
        try { return JSON.writeValueAsString(value).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029"); }
        catch (JsonProcessingException impossible) { throw new IllegalStateException("预览配置无法序列化"); }
    }
}
