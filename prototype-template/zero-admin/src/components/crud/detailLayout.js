/**
 * 统计已解析 Grid 模板中的顶层轨道数量。
 *
 * 浏览器会把 auto-fit/repeat 解析为具体轨道，本方法忽略函数内部空格，避免
 * minmax() 等表达式被误判为多个轨道。
 *
 * @param {string} templateColumns 已解析的 grid-template-columns。
 * @returns {number} Grid 轨道数量，无法识别时返回 1。
 */
export const countResolvedGridColumns = (templateColumns) => {
  const source = String(templateColumns || "").trim();
  if (!source || source === "none") return 1;

  let depth = 0;
  let trackCount = 0;
  let hasTrackContent = false;

  for (const character of source) {
    if (/\s/.test(character) && depth === 0) {
      if (hasTrackContent) {
        trackCount += 1;
        hasTrackContent = false;
      }
      continue;
    }

    hasTrackContent = true;
    if (character === "(") depth += 1;
    if (character === ")") depth = Math.max(0, depth - 1);
  }

  return Math.max(1, trackCount + Number(hasTrackContent));
};

/**
 * 计算详情字段的自动横跨列数。
 *
 * full 字段是独立行边界；边界之前和详情末尾的普通字段如果未占满一行，
 * 由该段最后一个普通字段横跨剩余列。full 字段自身仍由 CSS 占满整行。
 *
 * @param {boolean[]} fullStates 字段是否显式占满整行的状态序列。
 * @param {number} columnCount 当前实际列数。
 * @returns {number[]} 与字段顺序一致的自动横跨列数。
 */
export const resolveDetailItemSpans = (fullStates, columnCount) => {
  const safeColumnCount = Math.max(1, Math.floor(Number(columnCount) || 1));
  const spans = fullStates.map(() => 1);
  if (safeColumnCount === 1) return spans;

  let segmentStart = 0;
  const fillSegmentRemainder = (segmentEnd) => {
    const itemCount = segmentEnd - segmentStart;
    const remainder = itemCount % safeColumnCount;
    if (remainder > 0) {
      spans[segmentEnd - 1] = safeColumnCount - remainder + 1;
    }
  };

  fullStates.forEach((isFull, index) => {
    if (!isFull) return;
    fillSegmentRemainder(index);
    segmentStart = index + 1;
  });
  fillSegmentRemainder(fullStates.length);

  return spans;
};
