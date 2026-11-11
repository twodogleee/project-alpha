/** 数据库目录编辑副本与密钥策略验证。@author Andersen */
import test from 'node:test';
import assert from 'node:assert/strict';
import { catalogFetchPayload, changeModelPlatform, createModelEditor, createPlatformEditor, modelConfigurationPayload, platformPayload, validModelEditor, validPlatformEditor } from '../src/model/configuration.js';

/** 模型编辑不接受服务端误附的凭证或密文。 */
test('模型副本只含公开字段，提交采用白名单和目录版本', () => {
  const saved = { id: 'm1', platformId: 'p1', name: '模型一', model: 'test-model', apiKeyConfigured: true, apiKey: 'server-secret', apiKeyCiphertext: 'cipher' };
  const editor = createModelEditor(saved);
  assert.equal(editor.apiKey, ''); assert.equal(editor.apiKeyCiphertext, undefined);
  editor.extra = 'ignored';
  assert.deepEqual(modelConfigurationPayload(editor, 7), { expectedRevision: 7, platformId: 'p1', name: '模型一', model: 'test-model', apiKey: '', clearApiKey: false });
  assert.equal(validModelEditor(editor, [{ id: 'p1' }], saved), true);
});
/** 移动平台不会将表单或原密钥隐式沿用。 */
test('移动平台清空暂存密钥，原模型有密钥时必须新密钥或明确清除', () => {
  const saved = { platformId: 'p1', name: '模型', model: 'test', apiKeyConfigured: true };
  const editor = createModelEditor(saved); editor.apiKey = 'temporary'; changeModelPlatform(editor, 'p2');
  assert.equal(editor.apiKey, ''); assert.equal(validModelEditor(editor, [{ id: 'p2' }], saved), false);
  editor.clearApiKey = true; assert.equal(validModelEditor(editor, [{ id: 'p2' }], saved), true);
});
/** 未配置模型可先保存，非法模型或凭证命令仍拒绝。 */
test('无密钥可以先保存模型，模型标识和清除命令必须有效', () => {
  const editor = createModelEditor(null, 'p1'); editor.name = '新模型'; editor.model = 'model:v1';
  assert.equal(validModelEditor(editor, [{ id: 'p1' }]), true);
  editor.apiKey = 'new-key'; editor.clearApiKey = true; assert.equal(validModelEditor(editor, [{ id: 'p1' }]), false);
  editor.apiKey = ''; editor.model = 'invalid name'; assert.equal(validModelEditor(editor, [{ id: 'p1' }]), false);
});
/** 平台编辑不包含硬编码平台名单或默认 URL。 */
test('新增平台地址由用户填写，提交仅含协议和公开元数据', () => {
  const editor = createPlatformEditor(); assert.equal(editor.baseUrl, ''); assert.equal(validPlatformEditor(editor), false);
  editor.name = ' 我的平台 '; editor.baseUrl = ' https://example.com/v1 '; editor.secret = 'ignored';
  assert.equal(validPlatformEditor(editor), true);
  assert.deepEqual(platformPayload(editor, 3), { expectedRevision: 3, name: '我的平台', protocol: 'openai-compatible', baseUrl: 'https://example.com/v1', modelCatalogUrl: '' });
});
/** 目录凭证只来自当前编辑模型，且获取不依赖尚未填写的模型名称和标识。 */
test('目录请求只包含当前凭证策略，新增时不指定已有模型', () => {
  const editor = createModelEditor(null, 'p1'); editor.apiKey = ' temporary '; editor.extra = 'ignored';
  assert.deepEqual(catalogFetchPayload(editor, 5), { expectedRevision: 5, modelId: null, apiKey: 'temporary', clearApiKey: false });
  editor.apiKey = ''; editor.clearApiKey = true;
  assert.deepEqual(catalogFetchPayload(editor, 5, 'm1'), { expectedRevision: 5, modelId: 'm1', apiKey: '', clearApiKey: true });
});
/** 清空目录地址作为明确命令发送，同源校验包括协议、端口和 URL 中凭证。 */
test('目录地址可以清空，只允许同源且不携带 URL 凭证', () => {
  const editor = createPlatformEditor({ name: '平台', baseUrl: 'https://example.com/v1', modelCatalogUrl: 'https://example.com:443/v1/models' });
  assert.equal(validPlatformEditor(editor), true);
  for (const invalid of ['https://other.example/v1/models', 'https://example.com:8443/v1/models',
    'http://example.com/v1/models', 'https://user:password@example.com/v1/models', 'https://example.com/v1/models?api_key=value',
    'https://example.com/v1/models#value', '/v1/models']) {
    editor.modelCatalogUrl = invalid; assert.equal(validPlatformEditor(editor), false, invalid);
  }
  editor.modelCatalogUrl = ' '; assert.equal(validPlatformEditor(editor), true);
  assert.equal(platformPayload(editor, 6).modelCatalogUrl, '');
});
