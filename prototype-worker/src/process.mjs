/** 有界子进程执行；命令与参数由平台生成，不经过 shell。@author Andersen */
import { spawn } from 'node:child_process';
import { mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { PrototypeError } from './errors.mjs';
import { limits } from './config.mjs';

/** 执行平台命令，限制输出与时间，取消时清理编译进程组。 */
export function runProcess(command, args, { timeoutMs = 30_000, maxOutput = limits.logBytes, cwd, env, signal, onTimeout } = {}) {
  return new Promise((resolve, reject) => {
    signal?.throwIfAborted();
    const child = spawn(command, args, { cwd, env, shell: false, detached: process.platform !== 'win32', stdio: ['ignore', 'pipe', 'pipe'] });
    const stdout = [];
    const stderr = [];
    let size = 0;
    let failure = null;
    /** 清理回调只发起一次，实际完成由外层 finally 再次核对。 */
    function stop(error) {
      if (failure) return;
      failure = error;
      try {
        if (process.platform !== 'win32' && child.pid) process.kill(-child.pid, 'SIGKILL');
        else child.kill('SIGKILL');
      } catch { /* 进程可能已退出，管道仍必须主动关闭。 */ }
      child.stdout.destroy();
      child.stderr.destroy();
      clearTimeout(timer);
      Promise.resolve().then(() => onTimeout?.()).catch(() => {});
      reject(error);
    }
    /** 仅保留限制内输出；超过限制先终止，再返回稳定错误。 */
    function collect(chunks, chunk) {
      if (failure) return;
      size += chunk.length;
      if (size > maxOutput) {
        stop(new PrototypeError('PROTOTYPE_OUTPUT_LIMIT', '执行输出超过限制'));
      } else chunks.push(chunk);
    }
    child.stdout.on('data', chunk => collect(stdout, chunk));
    child.stderr.on('data', chunk => collect(stderr, chunk));
    const timer = setTimeout(() => {
      stop(new PrototypeError('PROTOTYPE_TIMEOUT', '执行超过时间限制'));
    }, timeoutMs);
    const abort = () => stop(new PrototypeError('PROTOTYPE_TIMEOUT', '本次原型编译已取消'));
    signal?.addEventListener('abort', abort, { once: true });
    child.once('close', () => signal?.removeEventListener('abort', abort));
    child.once('error', () => { clearTimeout(timer); reject(new PrototypeError('PROTOTYPE_RUNNER_UNAVAILABLE', '执行工具不可用')); });
    child.once('close', code => {
      clearTimeout(timer);
      if (failure) reject(failure);
      else resolve({ code, stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr) });
    });
  });
}

/** 本机执行槽使用原子目录；遗留记录需显式核对，避免并发回收误删新执行槽。 */
export async function acquireExecutionSlot(artifactRoot) {
  await mkdir(artifactRoot, { recursive: true });
  const directory = resolve(artifactRoot, 'execution.lock');
  const ownerPath = resolve(directory, 'owner.json');
  const token = randomUUID();
  try { await mkdir(directory); }
  catch (error) {
    if (error.code !== 'EEXIST') throw error;
    let previous;
    try { previous = JSON.parse(await readFile(ownerPath, 'utf8')); } catch { throw new PrototypeError('PROTOTYPE_RUNNER_BUSY', '执行槽记录尚未就绪或需要人工核对'); }
    if (!Number.isSafeInteger(previous.pid) || previous.pid <= 0) throw new PrototypeError('PROTOTYPE_RUNNER_BUSY', '执行槽记录无效，请核对运行进程');
    let alive = true;
    try { process.kill(previous.pid, 0); } catch (failure) { if (failure.code === 'ESRCH') alive = false; }
    if (alive) throw new PrototypeError('PROTOTYPE_RUNNER_BUSY', '已有原型构建正在执行，请等待结束');
    throw new PrototypeError('PROTOTYPE_RUNNER_STALE_LOCK', '检测到中断进程的执行槽；请确认没有原型任务运行后清理 execution.lock，再显式重试');
  }
  await writeFile(ownerPath, JSON.stringify({ pid: process.pid, token }), { flag: 'wx', mode: 0o600 });
  /** 只有本次持有者可以释放槽，避免异常清理删除新的运行记录。 */
  return async function release() {
    try { if (JSON.parse(await readFile(ownerPath, 'utf8')).token === token) await rm(directory, { recursive: true }); } catch { /* 已释放的槽不重复操作。 */ }
  };
}
