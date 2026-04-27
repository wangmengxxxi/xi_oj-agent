content_type: 语言陷阱
tag: Java,Integer
title: Integer缓存池陷阱（-128~127）

【问题描述】
Java 对 -128 到 127 范围内的 Integer 对象做了缓存（IntegerCache），在此范围内 Integer.valueOf() 返回同一对象，== 比较为 true。但超出此范围时，每次 valueOf() 创建新对象，== 比较为 false，即使数值相同。

【典型错误】
Integer a = 128; Integer b = 128; if (a == b) 结果为 false。在 OJ 中常见于用 Integer 作为 HashMap 的 key 后取出比较，或者排序后比较相邻元素。

【正确做法】
始终使用 a.equals(b) 比较 Integer 值，或用 intValue() 转为基本类型后用 == 比较。

---
content_type: 语言陷阱
tag: Java,String
title: String比较必须用equals而非==

【问题描述】
Java 中 == 比较的是对象引用地址，不是字符串内容。字符串字面量会被放入常量池复用，但通过 new String()、substring()、split() 等方式产生的字符串是新对象，== 比较为 false。

【典型错误】
String s = scanner.next(); if (s == "yes") 永远为 false，因为 scanner 读入的字符串不在常量池中。

【正确做法】
使用 s.equals("yes") 或 "yes".equals(s)（后者可防空指针）。

---
content_type: 语言陷阱
tag: Java,Scanner
title: Scanner的nextInt后nextLine吞换行符问题

【问题描述】
Scanner.nextInt()、nextDouble() 等方法只读取数值，不消耗行末的换行符 \n。紧接着调用 nextLine() 会读到空字符串而非下一行内容，导致输入错位。

【典型错误】
int n = scanner.nextInt(); String line = scanner.nextLine(); 此时 line 为空字符串，真正的下一行被跳过。

【正确做法】
方案一：在 nextInt() 后加一行 scanner.nextLine() 消耗换行符。方案二：统一用 nextLine() 读取后手动 Integer.parseInt() 解析。方案三：使用 BufferedReader 替代 Scanner，性能更好且无此问题。

---
content_type: 语言陷阱
tag: Java,数组排序
title: int[]数组不能直接使用自定义Comparator排序

【问题描述】
Arrays.sort(int[]) 只能升序排序，不接受 Comparator 参数。如果需要降序排序或自定义排序规则，必须使用 Integer[] 包装类型数组。

【典型错误】
int[] arr = {3,1,2}; Arrays.sort(arr, (a,b) -> b-a); 编译错误，因为 Arrays.sort 对基本类型数组没有 Comparator 重载。

【正确做法】
方案一：使用 Integer[] 数组配合 Comparator。方案二：先升序排序再手动反转。方案三：使用 IntStream.of(arr).boxed().sorted(Comparator.reverseOrder()).mapToInt(Integer::intValue).toArray()。

---
content_type: 语言陷阱
tag: Java,整数溢出
title: int乘法溢出必须提前转long

【问题描述】
Java 中 int 范围为 -2^31 到 2^31-1（约 ±21 亿）。两个 int 相乘结果仍为 int，超出范围会静默溢出而非报错。这是 OJ 中 WA 的高频原因。

【典型错误】
int a = 100000, b = 100000; int result = a * b; 结果溢出为 1410065408 而非 10000000000。即使赋值给 long 变量，乘法已经在 int 范围内溢出：long result = a * b; 仍然错误。

【正确做法】
在乘法前将至少一个操作数转为 long：long result = (long) a * b; 或者直接声明变量为 long 类型。数据范围 n ≥ 10^5 时，涉及乘法的中间结果几乎都需要 long。

---
content_type: 语言陷阱
tag: Java,HashMap
title: 遍历HashMap时修改会抛ConcurrentModificationException

【问题描述】
使用 for-each 或 Iterator 遍历 HashMap/ArrayList 时，如果在循环体内直接调用 map.put()、map.remove() 或 list.add()、list.remove()，会触发 ConcurrentModificationException。

【典型错误】
for (Map.Entry entry : map.entrySet()) { if (条件) map.remove(entry.getKey()); } 运行时抛异常。

【正确做法】
方案一：使用 Iterator 的 remove() 方法。方案二：先收集要删除的 key 到临时 List，遍历结束后统一删除。方案三：使用 removeIf()：map.entrySet().removeIf(e -> 条件)。

---
content_type: 语言陷阱
tag: Java,递归
title: Java递归深度限制与栈溢出

【问题描述】
Java 默认线程栈大小约 512KB~1MB，每层递归调用消耗一个栈帧（通常几十到几百字节）。当递归深度超过约 5000~10000 层时，会抛出 StackOverflowError。DFS 遍历大图、深度递归的树问题容易触发。

【典型错误】
对 n=100000 的链表做递归遍历，或对深度为 n 的树做递归 DFS，直接栈溢出。

【正确做法】
方案一：将递归改为迭代，用显式栈模拟递归过程。方案二：使用 -Xss 参数增大栈空间（OJ 环境通常不允许）。方案三：对于尾递归场景，手动改写为循环。

---
content_type: 语言陷阱
tag: Java,char
title: char与int隐式转换导致的计算错误

【问题描述】
Java 中 char 本质是 16 位无符号整数，参与算术运算时自动提升为 int。字符 '0' 的值是 48，'A' 是 65，'a' 是 97。直接用 char 做加减法得到的是 ASCII 码值而非预期的数字或字母。

【典型错误】
char c = '5'; int num = c; 此时 num 为 53 而非 5。String s = "" + ('a' + 1); 结果为 "98" 而非 "b"。

【正确做法】
字符转数字：int num = c - '0'。数字转字符：char c = (char)(num + '0')。字母偏移：char next = (char)(c + 1)，注意强转回 char。

---
content_type: 语言陷阱
tag: Java,PriorityQueue
title: PriorityQueue不是有序遍历的数据结构

【问题描述】
Java 的 PriorityQueue 只保证堆顶元素是最小（或最大），不保证内部元素有序。直接用 for-each 或 iterator 遍历 PriorityQueue 得到的顺序不是排序顺序。

【典型错误】
PriorityQueue<Integer> pq = new PriorityQueue<>(); 添加元素后用 for (int x : pq) 遍历，期望得到有序输出，实际得到的是堆的内部存储顺序。

【正确做法】
如果需要有序输出，必须反复调用 poll() 逐个取出堆顶元素。如果需要有序集合，使用 TreeSet 或 TreeMap。

---
content_type: 语言陷阱
tag: Java,泛型擦除
title: Java泛型擦除导致的运行时类型丢失

【问题描述】
Java 泛型在编译后会被擦除（Type Erasure），运行时 List<Integer> 和 List<String> 都变成 List。这意味着不能用 instanceof 检查泛型类型，不能创建泛型数组 new T[]，不能对泛型类型做强制转换。

【典型错误】
在 OJ 中创建泛型数组：List<Integer>[] graph = new ArrayList<Integer>[n]; 编译错误。正确写法：List<Integer>[] graph = new ArrayList[n]; 或使用 List<List<Integer>>。

【正确做法】
用 ArrayList<ArrayList<Integer>> 替代泛型数组。需要泛型数组时用 (T[]) new Object[n] 强转（会有 unchecked 警告但可用）。

---
content_type: 语言陷阱
tag: Java,IO性能
title: Scanner性能瓶颈与BufferedReader替代方案

【问题描述】
Scanner 内部使用正则表达式解析输入，在大数据量（n ≥ 10^5）时性能远低于 BufferedReader。同样，System.out.println 在循环中频繁调用时也有性能问题。

【典型错误】
用 Scanner 读取 10^6 行输入，仅 IO 就耗时数秒，导致 TLE。

【正确做法】
输入用 BufferedReader + StringTokenizer：BufferedReader br = new BufferedReader(new InputStreamReader(System.in)); StringTokenizer st = new StringTokenizer(br.readLine()); int n = Integer.parseInt(st.nextToken())。输出用 StringBuilder 拼接后一次性 System.out.print，或用 PrintWriter 配合 flush。

---
content_type: 语言陷阱
tag: Java,Arrays.sort
title: Arrays.sort对基本类型和对象类型的排序差异

【问题描述】
Arrays.sort(int[]) 使用双轴快排（Dual-Pivot Quicksort），平均 O(n log n) 但最坏 O(n²)，且不稳定。Arrays.sort(Integer[]) 或 Arrays.sort(Object[]) 使用 TimSort，最坏 O(n log n) 且稳定。在 OJ 中，精心构造的数据可以卡掉 int[] 的快排。

【典型错误】
对 int[] 排序后依赖相同元素的相对顺序，但基本类型排序不稳定。或者被特殊数据卡成 O(n²) 导致 TLE。

【正确做法】
如果担心被卡，先 shuffle 数组再排序。需要稳定排序时转为 Integer[] 或使用 Collections.sort。自定义排序必须用对象数组。

---
content_type: 语言陷阱
tag: Java,Collections
title: Collections.sort的Comparator必须满足传递性

【问题描述】
Java 的 TimSort 要求 Comparator 满足传递性：如果 compare(a,b) > 0 且 compare(b,c) > 0，则 compare(a,c) 必须 > 0。违反传递性会抛出 IllegalArgumentException 或产生不确定的排序结果。

【典型错误】
比较器中使用减法 (a, b) -> a - b，当 a 和 b 的差超过 int 范围时溢出，导致比较结果不一致。或者比较逻辑中存在"相等时返回正数"的 bug。

【正确做法】
使用 Integer.compare(a, b) 替代 a - b。确保比较器的三个性质：自反性（compare(a,a) == 0）、反对称性、传递性。

---
content_type: 语言陷阱
tag: Java,自动拆箱
title: 自动拆箱遇null导致NullPointerException

【问题描述】
Java 自动拆箱（unboxing）会将 Integer 转为 int，但如果 Integer 对象为 null，拆箱时抛出 NullPointerException。这在使用 Map.get() 返回值时尤其常见。

【典型错误】
Map<String, Integer> map = new HashMap<>(); int count = map.get("key"); 如果 key 不存在，map.get 返回 null，自动拆箱为 int 时抛 NPE。

【正确做法】
使用 map.getOrDefault("key", 0) 提供默认值。或者先判空：Integer val = map.get("key"); if (val != null) { ... }。
