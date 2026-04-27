content_type: 语言陷阱
tag: C++,数组越界
title: C++数组越界不报错导致隐蔽bug

【问题描述】
C++ 不对数组下标做边界检查，越界访问不会抛异常，而是读写相邻内存。这可能导致程序输出错误结果、覆盖其他变量、或在某些 OJ 上触发段错误（Segmentation Fault）。行为是未定义的（Undefined Behavior）。

【典型错误】
int arr[100]; arr[100] = 1; 访问了数组外的内存。或者循环条件写成 i <= n 而非 i < n，多访问一个位置。

【正确做法】
数组大小多开一些余量（如 n+5）。循环条件仔细检查。使用 vector 配合 at() 方法可以在调试时检测越界。

---
content_type: 语言陷阱
tag: C++,未初始化
title: C++局部变量未初始化导致随机值

【问题描述】
C++ 中局部变量（包括数组）不会自动初始化为 0，其值是内存中的随机垃圾数据。全局变量和 static 变量会自动初始化为 0。

【典型错误】
int dp[1001][1001]; 在函数内声明但未 memset，dp 数组中是随机值，导致 DP 结果完全错误。

【正确做法】
声明时初始化：int dp[1001][1001] = {}; 或使用 memset(dp, 0, sizeof(dp))。对于 bool/int 数组清零用 memset 即可，但 memset 只能设置 0 或 -1（按字节填充）。

---
content_type: 语言陷阱
tag: C++,cin/cout性能
title: cin/cout默认同步导致IO性能低下

【问题描述】
C++ 的 cin/cout 默认与 C 的 stdio 同步，且 cin 与 cout 绑定（每次 cin 前会 flush cout）。这两个特性在大数据量 IO 时严重影响性能，可能导致 TLE。

【典型错误】
读取 10^6 个整数时，使用默认 cin 比 scanf 慢 3-5 倍。

【正确做法】
在 main 函数开头加：ios::sync_with_stdio(false); cin.tie(nullptr); 关闭同步后 cin/cout 性能接近 scanf/printf。注意：关闭同步后不能混用 cin 和 scanf。

---
content_type: 语言陷阱
tag: C++,整数溢出
title: C++ int溢出是未定义行为

【问题描述】
与 Java 不同，C++ 中有符号整数溢出是未定义行为（UB），编译器可能做出任何优化假设。无符号整数溢出是定义好的（取模 2^n），但有符号整数溢出不保证环绕。

【典型错误】
int a = INT_MAX; a + 1 的结果不一定是 INT_MIN，编译器可能优化掉溢出检查。两个 int 相乘赋给 long long 时，乘法仍在 int 范围内计算。

【正确做法】
涉及大数乘法时显式转换：(long long)a * b。使用 1LL * a * b 快速转换。数据范围 > 2×10^9 时用 long long 声明变量。

---
content_type: 语言陷阱
tag: C++,STL迭代器失效
title: STL容器修改后迭代器失效

【问题描述】
对 vector 进行 push_back/insert/erase 操作后，之前获取的迭代器、指针和引用可能失效。对 map/set 进行 erase 后，被删除元素的迭代器失效，但其他迭代器仍有效。

【典型错误】
for (auto it = vec.begin(); it != vec.end(); ++it) { if (条件) vec.erase(it); } erase 后 it 失效，++it 是未定义行为。

【正确做法】
vector 删除：it = vec.erase(it)（erase 返回下一个有效迭代器，此时不要 ++it）。或者用 erase-remove 惯用法：vec.erase(remove_if(vec.begin(), vec.end(), pred), vec.end())。

---
content_type: 语言陷阱
tag: C++,引用悬垂
title: 返回局部变量的引用导致悬垂引用

【问题描述】
函数返回局部变量的引用或指针后，该变量已被销毁，引用指向无效内存。访问悬垂引用是未定义行为，可能得到垃圾值或段错误。

【典型错误】
int& getMax(int a, int b) { int result = max(a, b); return result; } 返回了局部变量 result 的引用。

【正确做法】
返回值而非引用：int getMax(int a, int b) { return max(a, b); }。如果确实需要返回引用，确保引用的对象生命周期长于引用本身（如类成员变量、static 变量、堆上对象）。

---
content_type: 语言陷阱
tag: C++,浮点精度
title: C++浮点数比较与精度陷阱

【问题描述】
浮点数（float/double）存在精度误差，0.1 + 0.2 != 0.3。直接用 == 比较两个浮点数几乎总是错误的。在 OJ 中，浮点精度问题常导致 WA。

【典型错误】
double a = 0.1 + 0.2; if (a == 0.3) 为 false。或者累加大量小数后误差累积导致结果偏差。

【正确做法】
比较时使用 eps：fabs(a - b) < 1e-9。尽量用整数运算替代浮点运算。如果题目要求输出小数，注意 printf("%.6f", ans) 的精度控制。

---
content_type: 语言陷阱
tag: C++,memset
title: memset只能安全地设置0或-1

【问题描述】
memset 按字节填充内存。对 int 数组 memset(arr, 0, sizeof(arr)) 可以正确清零（每字节 0x00，int 为 0x00000000 = 0）。memset(arr, -1, sizeof(arr)) 也可以（每字节 0xFF，int 为 0xFFFFFFFF = -1）。但 memset(arr, 1, sizeof(arr)) 不会得到 1，而是 0x01010101 = 16843009。

【典型错误】
memset(dp, 0x3f, sizeof(dp)) 用于初始化为"无穷大"（0x3f3f3f3f ≈ 10^9），这是正确的技巧。但 memset(dp, 1, sizeof(dp)) 期望得到全 1 数组是错误的。

【正确做法】
清零用 memset 0，初始化为 -1 用 memset -1，初始化为大值用 memset 0x3f。其他值用 fill(arr, arr+n, value) 或循环赋值。

---
content_type: 语言陷阱
tag: C++,string
title: C++ string的substr和find性能注意事项

【问题描述】
string::substr() 每次调用都会创建新字符串并复制内容，O(k) 时间和空间（k 为子串长度）。在循环中频繁调用 substr 会导致 O(n×k) 的额外开销。string::find() 是 O(n×m) 的朴素匹配。

【典型错误】
循环中对每个位置调用 s.substr(i, len) 进行比较，总时间 O(n × len)。

【正确做法】
用 s.compare(pos, len, target) 替代 s.substr(pos, len) == target，避免创建临时字符串。需要高效子串匹配时用 KMP 或字符串哈希。C++17 的 string_view 可以零拷贝引用子串。

---
content_type: 语言陷阱
tag: C++,全局数组
title: C++全局数组与局部数组的大小限制差异

【问题描述】
局部数组存储在栈上，栈大小通常只有 1-8MB，声明过大的局部数组会导致栈溢出（段错误）。全局数组存储在静态区，大小限制取决于系统内存，通常可以开到数百 MB。

【典型错误】
在 main 函数内声明 int dp[10000][10000]（约 400MB），直接段错误。

【正确做法】
大数组声明为全局变量。或者使用 vector 动态分配（堆上内存）。OJ 中常见做法：将 DP 数组、邻接表等大数据结构声明在全局。

---
content_type: 语言陷阱
tag: C++,运算符优先级
title: C++位运算优先级低于比较运算符

【问题描述】
C++ 中位运算符（&, |, ^）的优先级低于比较运算符（==, !=, <, >）。这意味着 a & b == c 实际上是 a & (b == c)，而非 (a & b) == c。

【典型错误】
if (mask & 1 == 1) 实际含义是 if (mask & (1 == 1)) 即 if (mask & 1)，虽然结果碰巧相同，但 if (x & 3 == 2) 就完全错误了。

【正确做法】
位运算表达式始终加括号：if ((mask & 1) == 1)、if ((a ^ b) > 0)。养成习惯，避免优先级陷阱。

---
content_type: 语言陷阱
tag: C++,map
title: C++ map的[]运算符会自动插入默认值

【问题描述】
对 map 使用 [] 运算符访问不存在的 key 时，会自动插入该 key 并赋默认值（int 为 0，string 为空串）。这意味着"查询"操作会修改 map 的大小，可能导致逻辑错误或 MLE。

【典型错误】
if (mp[key] == 0) 本意是检查 key 是否存在，但实际上如果 key 不存在，这行代码会插入 key=0，导致 map 不断膨胀。

【正确做法】
检查 key 是否存在用 mp.find(key) != mp.end() 或 mp.count(key) > 0。只在确定要插入/修改时才用 []。
