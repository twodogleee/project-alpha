/**
 * 同时启动前后端开发进程，并在退出或任一进程失败时清理另一进程。
 * @author Andersen
 */
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

/** 仓库根目录，不依赖调用脚本时的工作目录。 */
const rootDirectory = fileURLToPath(new URL('../', import.meta.url));
/** Windows 使用对应的可执行文件后缀。 */
const isWindows = process.platform === 'win32';
/** 需要随开发脚本退出的子进程。 */
const children = [];
/** 防止多次退出信号触发重复清理。 */
let isStopping = false;

/** 先停止子进程，再退出开发脚本；Unix 使用进程组清理 Gradle/npm 的子进程。 */
function stop(exitCode = 0) {
  if (isStopping) return;
  isStopping = true;
  for (const child of children) {
    if (!child.pid) continue;
    try {
      if (isWindows) {
        spawn('taskkill', ['/pid', String(child.pid), '/T', '/F']);
      } else {
        process.kill(-child.pid, 'SIGTERM');
      }
    } catch (error) {
      if (error.code !== 'ESRCH') console.error('开发进程清理失败:', error.code);
    }
  }
  process.exitCode = exitCode;
}

/** 启动一项开发服务，将输出直接连接终端。 */
function start(command, args) {
  const child = spawn(command, args, {
    cwd: rootDirectory,
    stdio: 'inherit',
    detached: !isWindows,
    shell: isWindows,
  });
  children.push(child);
  child.on('error', (error) => {
    console.error(`无法启动 ${command}: ${error.message}`);
    stop(1);
  });
  child.on('exit', (code) => stop(code ?? 1));
}

process.on('SIGINT', () => stop());
process.on('SIGTERM', () => stop());
start(isWindows ? 'gradlew.bat' : './gradlew', [':backend:bootRun', '--console=plain']);
start(isWindows ? 'npm.cmd' : 'npm', ['--prefix', 'frontend', 'run', 'dev']);
