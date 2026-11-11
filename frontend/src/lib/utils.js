/** shadcn-vue 组件类名合并，保留 Tailwind 覆盖语义。@author Andersen */
import { clsx } from "clsx";
import { twMerge } from "tailwind-merge";

/** 合并条件类名，并解决相互覆盖的 Tailwind 工具类。 */
export function cn(...inputs) {
  return twMerge(clsx(inputs));
}
