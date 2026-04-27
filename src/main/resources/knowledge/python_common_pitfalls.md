content_type: 语言陷阱
tag: Python,递归限制
title: Python默认递归深度限制为1000

【问题描述】
Python 默认递归深度限制为 1000 层（sys.getrecursionlimit()），超过后抛出 RecursionError。对于 n ≥ 1000 的 DFS、树遍历、递归 DP 都会触发。

【典型错误】
对 n=10000 的链表做递归遍历，或递归实现的 DFS 遍历大图，直接报 RecursionError。

【正确做法】
方案一：sys.setrecursionlimit(200000) 提高限制（但可能导致段错误，因为 Python 栈帧较大）。方案二：将递归改为迭代，用显式栈模拟。方案三：使用 BFS 替代 DFS。OJ 中推荐方案二。

---
content_type: 语言陷阱
tag: Python,可变默认参数
title: Python函数的可变默认参数陷阱

【问题描述】
Python 函数的默认参数在函数定义时只创建一次，后续调用共享同一个对象。如果默认参数是可变对象（list、dict、set），修改会在调用之间累积。

【典型错误】
def dfs(path=[]): path.append(1); return path。多次调用 dfs() 返回的 path 会越来越长，因为每次调用操作的是同一个 list 对象。

【正确做法】
使用 None 作为默认值，在函数内部创建新对象：def dfs(path=None): if path is None: path = []; ...。这是 Python 的标准惯用法。

---
content_type: 语言陷阱
tag: Python,浅拷贝
title: Python列表的浅拷贝导致意外修改

【问题描述】
Python 中 list.copy()、list[:]、list(original) 都是浅拷贝，只复制最外层列表，内部的嵌套对象仍然是引用。修改嵌套对象会影响原列表。

【典型错误】
board = [[0]*3 for _ in range(3)]; copy = [row[:] for row in board] 是正确的二维深拷贝。但 copy = board[:] 只拷贝了外层，内层 row 仍是共享引用。更常见的错误：board = [[0]*3]*3 创建的三行是同一个 list 对象的引用。

【正确做法】
二维数组正确创建：[[0]*n for _ in range(m)]（不要用 [[0]*n]*m）。二维数组深拷贝：[row[:] for row in board] 或 copy.deepcopy(board)。

---
content_type: 语言陷阱
tag: Python,TLE
title: Python执行速度慢导致TLE的常见场景

【问题描述】
Python 的执行速度约为 C++ 的 1/30 到 1/100。O(n²) 在 n=10^4 时 C++ 可以通过但 Python 大概率 TLE。Python 的循环、函数调用、对象创建开销都远大于编译型语言。

【典型错误】
用纯 Python 循环实现 O(n²) 算法，n=10^4 时超时。或者在循环中频繁创建临时列表/字符串。

【正确做法】
尽量降低算法复杂度。利用内置函数（sorted、sum、min、max 等，底层是 C 实现）。字符串拼接用 ''.join(list)。大量数值计算考虑用 numpy。输入输出用 sys.stdin.readline 替代 input()。

---
content_type: 语言陷阱
tag: Python,整数
title: Python大整数运算的性能陷阱

【问题描述】
Python 原生支持任意精度整数，不会溢出。但大整数运算（位数超过几千位）的时间复杂度不再是 O(1)，乘法是 O(n log n)（n 为位数）。在涉及大数的循环中，运算时间可能远超预期。

【典型错误】
计算 2^(10^6) 或大数阶乘时，每次乘法的操作数位数不断增长，总时间远超 O(n)。

【正确做法】
如果只需要结果对某个数取模，在每步运算后立即取模，保持数字在合理范围内：pow(a, n, MOD) 比 pow(a, n) % MOD 快得多（前者是模幂运算，后者先算出天文数字再取模）。

---
content_type: 语言陷阱
tag: Python,输入输出
title: Python的input()性能瓶颈与替代方案

【问题描述】
Python 的 input() 函数内部调用 sys.stdin.readline() 后还会 strip 末尾换行符，且每次调用有额外开销。在读取 10^5 行以上数据时，input() 的累积开销可能导致 TLE。

【典型错误】
n = int(input()); for _ in range(n): x = int(input()) 读取 10^6 个数，仅 IO 就耗时数秒。

【正确做法】
import sys; input = sys.stdin.readline 在文件开头重定义 input，后续代码无需修改。或者一次性读入所有数据：data = sys.stdin.read().split(); 用指针逐个取值。

---
content_type: 语言陷阱
tag: Python,全局变量
title: Python函数内访问全局变量的性能差异

【问题描述】
Python 访问局部变量比全局变量快约 20-30%，因为局部变量通过数组索引访问（LOAD_FAST），全局变量通过字典查找访问（LOAD_GLOBAL）。在性能敏感的 OJ 题目中，这个差异可能影响是否 TLE。

【典型错误】
将大量逻辑写在全局作用域（模块级别），所有变量都是全局变量，循环性能较差。

【正确做法】
将主要逻辑封装在 main() 函数中，在函数内部使用局部变量。文件末尾调用 main()。这是 Python OJ 选手的常见优化技巧。

---
content_type: 语言陷阱
tag: Python,列表推导
title: Python列表操作的时间复杂度陷阱

【问题描述】
list.insert(0, x) 和 list.pop(0) 是 O(n) 操作（需要移动所有元素），不是 O(1)。在循环中频繁在列表头部插入/删除会导致 O(n²) 总时间。list.append() 和 list.pop() 是 O(1) 均摊。

【典型错误】
用 list 模拟队列：queue.pop(0) 取队头元素，每次 O(n)，总时间 O(n²)。

【正确做法】
使用 collections.deque 作为队列：deque.popleft() 和 deque.appendleft() 都是 O(1)。deque 也支持 append() 和 pop()，可以同时当栈和队列使用。

---
content_type: 语言陷阱
tag: Python,字典
title: Python字典的defaultdict与Counter使用技巧

【问题描述】
普通 dict 访问不存在的 key 会抛 KeyError。在 OJ 中频繁需要统计频率或分组，每次都写 if key in dict 很繁琐且容易出错。

【典型错误】
for x in arr: count[x] += 1 如果 count 是普通 dict 且 x 不存在，抛 KeyError。

【正确做法】
使用 collections.defaultdict(int) 自动初始化为 0。使用 collections.Counter(arr) 一行完成频率统计。Counter 还支持 most_common(k) 取前 K 高频元素、Counter 之间的加减运算。

---
content_type: 语言陷阱
tag: Python,排序
title: Python排序的key参数与cmp_to_key

【问题描述】
Python 的 sorted() 和 list.sort() 只接受 key 参数，不直接支持自定义比较函数。需要自定义比较逻辑时，必须用 functools.cmp_to_key 将比较函数转换为 key 函数。

【典型错误】
sorted(arr, cmp=lambda a,b: a-b) 在 Python 3 中报错（Python 2 语法）。

【正确做法】
from functools import cmp_to_key; sorted(arr, key=cmp_to_key(lambda a,b: a-b))。简单情况优先用 key 参数：sorted(arr, key=lambda x: x[1]) 按第二个元素排序。多关键字排序：sorted(arr, key=lambda x: (x[0], -x[1]))。

---
content_type: 语言陷阱
tag: Python,集合
title: Python的set和frozenset在OJ中的应用

【问题描述】
Python 的 set 是基于哈希表的无序集合，支持 O(1) 的 in 判断、add、remove。但 set 本身不可哈希，不能作为 dict 的 key 或放入另一个 set。需要不可变集合时用 frozenset。

【典型错误】
visited = set(); visited.add([1,2]) 报错，因为 list 不可哈希。或者想用集合作为状态存入 visited set 中。

【正确做法】
将 list 转为 tuple 后加入 set：visited.add(tuple(state))。需要集合作为 key 时用 frozenset。判断元素是否存在用 in 运算符（O(1)），不要用 list 的 in（O(n)）。
