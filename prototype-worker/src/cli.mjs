#!/usr/bin/env node
/** 本地 P1/P2 操作入口，默认不创建业务任务或调用模型。@author Andersen */
import { doctor, prepareToolchain, buildFixture, buildGenerated } from './runner.mjs';
import { readCurrentArtifact } from './artifacts.mjs';
import { PrototypeError } from './errors.mjs';

/** 受信 Java 通过 stdin 提交 JSON，限制编码后的总量且不接受路径参数。 */
async function readGeneratedRequest() {
  const chunks = [];
  let bytes = 0;
  for await (const chunk of process.stdin) {
    bytes += chunk.length;
    if (bytes > 32 * 1024 * 1024) throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成请求超过协议容量');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new PrototypeError('PROTOTYPE_FILE_REJECTED', '生成请求不是有效 JSON'); }
}

/** 仅开放固定命令，不接收任意 shell、镜像或目录参数。 */
async function main() {
  const command = process.argv[2];
  if (process.argv.length !== 3) throw new Error('用法：node src/cli.mjs doctor|prepare|build-fixture|build-generated|preview');
  if (command === 'doctor') {
    const result = await doctor();
    process.stdout.write(JSON.stringify(result, null, 2) + '\n');
    if (!result.available) process.exitCode = 1;
  } else if (command === 'prepare') {
    process.stdout.write(JSON.stringify(await prepareToolchain(), null, 2) + '\n');
  } else if (command === 'build-fixture') {
    const artifact = await buildFixture();
    process.stdout.write(JSON.stringify({ id: artifact.id, distDir: artifact.distDir, validation: artifact.manifest.validation, notice: artifact.manifest.notice }, null, 2) + '\n');
  } else if (command === 'build-generated') {
    const artifact = await buildGenerated(await readGeneratedRequest());
    process.stdout.write(JSON.stringify({ protocolVersion: 1, status: 'PASSED', artifactId: artifact.id, validation: artifact.manifest.validation }) + '\n');
  } else if (command === 'preview') {
    const artifact = await readCurrentArtifact();
    const { startPreview } = await import('./preview.mjs');
    const preview = await startPreview({ artifactDirectory: artifact.distDir, revisionId: artifact.id });
    process.stdout.write(JSON.stringify({ hostUrl: preview.hostUrl, previewUrl: preview.previewUrl, notice: artifact.manifest.notice }, null, 2) + '\n');
    /** 仅关闭本次启动的预览服务。 */
    async function shutdown() { await preview.close(); process.exit(0); }
    process.once('SIGINT', shutdown); process.once('SIGTERM', shutdown);
  } else throw new Error('未知命令；仅支持 doctor、prepare、build-fixture、build-generated、preview');
}

main().catch(error => {
  process.stderr.write(JSON.stringify({ code: error.code || 'PROTOTYPE_COMMAND_FAILED', message: String(error.message).slice(0, 1000), diagnostic: error.diagnostic ? String(error.diagnostic).slice(0, 12000) : undefined }, null, 2) + '\n');
  process.exitCode = 1;
});
