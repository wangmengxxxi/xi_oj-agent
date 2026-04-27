content_type: 知识点
tag: OJ技巧,时间复杂度
title: 根据数据范围估算可接受的时间复杂度

【核心规则】
OJ 一般 1 秒内可执行约 10^8 次简单操作（C++），Java 约 10^7，Python 约 10^6。根据数据范围 n 反推算法复杂度上限：
- n ≤ 10：O(n!) 或 O(2^n) — 全排列、暴力枚举
- n ≤ 20：O(2^n) — 状态压缩 DP、子集枚举
- n ≤ 500：O(n³) — Floyd、区间 DP
- n ≤ 5000：O(n²) — 朴素 DP、双重循环
- n ≤ 10^5：O(n log n) — 排序、二分、线段树
- n ≤ 10^6：O(n) — 双指针、哈希表、前缀和
- n ≤ 10^9：O(log n) 或 O(√n) — 二分答案、数学公式

【实战建议】
拿到题目先看数据范围，确定算法复杂度上限，再选择对应的算法。这是避免 TLE 的第一步。

---
content_type: 知识点
tag: OJ技巧,边界测试
title: 构造边界测试用例的系统方法

【核心思路】
大部分 WA 都出在边界条件上。提交前应手动构造以下测试用例：
1. 最小输入：n=0、n=1、空数组、空字符串
2. 最大输入：n 取数据范围上限，检查是否超时或溢出
3. 全相同元素：[1,1,1,1,1]
4. 极端值：全正数、全负数、包含 0、包含 INT_MAX/INT_MIN
5. 有序输入：已排序、逆序排序
6. 单一答案：只有一个合法解的情况

【实战建议】
先用题目给的样例验证基本逻辑，再用边界用例验证鲁棒性。如果样例通过但提交 WA，大概率是边界条件遗漏。

---
content_type: 知识点
tag: OJ技巧,调试
title: OJ调试技巧-快速定位错误

【常用方法】
1. 打印中间变量：在关键位置输出变量值，对比预期。提交前记得删除或注释掉调试输出
2. 对拍法：写一个暴力解法（保证正确但慢），随机生成大量测试数据，对比两个程序的输出，找到第一个不一致的用例
3. 缩小范围：如果大数据 WA，尝试用二分法缩小数据规模，找到最小的出错用例
4. 手动模拟：对小规模输入逐步模拟算法执行过程，画图辅助理解

【对拍脚本模板（bash）】
while true; do python3 gen.py > input.txt && ./brute < input.txt > out1.txt && ./solution < input.txt > out2.txt && diff out1.txt out2.txt || break; done

---
content_type: 知识点
tag: OJ技巧,输入输出
title: 各语言IO优化方案汇总

【Java IO 优化】
慢：Scanner + System.out.println。快：BufferedReader + StringTokenizer 读入，StringBuilder + System.out.print 输出。速度差距可达 5-10 倍。

【C++ IO 优化】
在 main 开头加 ios::sync_with_stdio(false); cin.tie(nullptr); 关闭同步后 cin/cout 性能接近 scanf/printf。不要混用 cin 和 scanf。

【Python IO 优化】
import sys; input = sys.stdin.readline 重定义 input。或一次性读入：data = sys.stdin.buffer.read().decode().split()。输出用 sys.stdout.write() 替代 print()。

---
content_type: 知识点
tag: OJ技巧,常见数据结构选择
title: 根据操作需求选择合适的数据结构

【选择指南】
- 频繁查找是否存在 → HashSet / unordered_set / set（O(1)）
- 频繁统计频率 → HashMap / unordered_map / Counter
- 需要有序 + 查找 → TreeMap / TreeSet / map / set（O(log n)）
- 频繁在两端操作 → Deque / deque
- 需要动态获取最值 → PriorityQueue / priority_queue / heapq
- 需要区间查询修改 → 线段树 / 树状数组
- 需要动态连通性 → 并查集
- 需要前缀匹配 → Trie

【实战建议】
先明确需要哪些操作及其频率，再选择数据结构。错误的数据结构选择是 TLE 的常见原因（如用 list 做频繁查找）。

---
content_type: 知识点
tag: OJ技巧,取模
title: 取模运算的正确姿势与常见公式

【为什么要取模】
OJ 中答案可能非常大（如组合数、路径计数），题目要求对 10^9+7（质数）取模。取模的目的是防止整数溢出并验证算法正确性。

【取模规则】
(a + b) % M = ((a % M) + (b % M)) % M
(a - b) % M = ((a % M) - (b % M) + M) % M（加 M 防负数）
(a × b) % M = ((a % M) × (b % M)) % M
(a / b) % M = (a × b^(M-2)) % M（费马小定理求逆元，要求 M 为质数）

【实战建议】
在每次加法和乘法后立即取模。减法取模记得加 M。除法必须转为乘逆元。10^9+7 是质数，可以用费马小定理。

---
content_type: 知识点
tag: OJ技巧,空间优化
title: 常见空间优化技巧

【滚动数组】
当 DP 状态只依赖前一行或前两行时，不需要开完整的二维数组。用两行交替（dp[i%2][j]）或直接压缩为一维数组。空间从 O(n²) 降到 O(n)。

【原地修改】
如果允许修改输入数组，可以用输入数组本身存储中间结果，省去额外空间。例如前缀和可以原地计算。

【位压缩】
用 int 的每一位表示一个 bool 值，32 个 bool 只需一个 int。适用于状态压缩 DP 和集合操作。

【实战建议】
先写出正确的解法，再考虑空间优化。OJ 的内存限制通常为 256MB，int 数组 10^7 约 40MB，一般够用。

---
content_type: 知识点
tag: OJ技巧,图的存储
title: 图的三种存储方式与适用场景

【邻接矩阵】
int[][] graph = new int[n][n]。适用于稠密图（边数接近 n²）或需要 O(1) 查询两点是否相连。空间 O(n²)，n ≤ 1000 时可用。

【邻接表】
List<List<int[]>> graph，每个节点存储其邻居列表。适用于稀疏图（边数远小于 n²），空间 O(n + m)。OJ 中最常用的存储方式。

【链式前向星】
用数组模拟链表存储边，空间紧凑，cache 友好。适用于对性能要求极高的场景（C++ 竞赛选手常用）。

【选择建议】
默认用邻接表。n ≤ 500 且需要频繁查询边是否存在时用邻接矩阵。追求极致性能用链式前向星。
