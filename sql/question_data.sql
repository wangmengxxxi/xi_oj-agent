-- ----------------------------
-- xi oj 题目示例数据
-- 共 8 道题：入门 2 道、简单 4 道、中等 2 道
-- userId 使用已有的 admin 用户：2037025038146736130
-- ----------------------------

INSERT INTO `question`
  (`title`, `content`, `tags`, `answer`, `submitNum`, `acceptedNum`,
   `judgeCase`, `judgeConfig`, `thumbNum`, `favourNum`, `userId`)
VALUES

-- ============================================================
-- 1. A+B 问题（入门）
-- ============================================================
(
  'A+B 问题',
  '## 题目描述\n\n计算两个整数 A 和 B 的和。\n\n## 输入格式\n\n输入一行，包含两个整数 A 和 B，用空格分隔。\n\n## 输出格式\n\n输出一个整数，表示 A + B 的结果。\n\n## 示例\n\n**输入样例**\n\n```\n1 2\n```\n\n**输出样例**\n\n```\n3\n```\n\n## 数据范围\n\n- -10^9 ≤ A, B ≤ 10^9',
  '["入门", "模拟"]',
  '## 参考答案（Java）\n\n```java\nimport java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        long a = sc.nextLong();\n        long b = sc.nextLong();\n        System.out.println(a + b);\n    }\n}\n```\n\n## 思路分析\n\n直接读入两个整数求和输出即可。注意当 A、B 均取 10^9 时，结果为 2×10^9，超过 int 范围（约 2.1×10^9），建议使用 `long` 类型存储结果。',
  1523, 1287,
  '[{"input":"1 2","output":"3"},{"input":"-5 10","output":"5"},{"input":"0 0","output":"0"},{"input":"1000000000 999999999","output":"1999999999"}]',
  '{"timeLimit":1000,"memoryLimit":262144,"stackLimit":262144}',
  45, 23,
  2037025038146736130
),

-- ============================================================
-- 2. 回文字符串判断（入门）
-- ============================================================
(
  '回文字符串判断',
  '## 题目描述\n\n给定一个只包含小写英文字母的字符串 S，判断它是否为回文字符串。\n\n回文字符串是指正读和反读都相同的字符串，例如 `abcba`、`abba`。\n\n## 输入格式\n\n输入一行，为字符串 S（只含小写英文字母）。\n\n## 输出格式\n\n若 S 是回文字符串，输出 `YES`；否则输出 `NO`。\n\n## 示例\n\n**输入样例 1**\n\n```\nabcba\n```\n\n**输出样例 1**\n\n```\nYES\n```\n\n**输入样例 2**\n\n```\nhello\n```\n\n**输出样例 2**\n\n```\nNO\n```\n\n## 数据范围\n\n- 1 ≤ |S| ≤ 10^5',
  '["字符串", "双指针", "入门"]',
  '## 参考答案（Java）\n\n```java\nimport java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        String s = sc.nextLine().trim();\n        int l = 0, r = s.length() - 1;\n        while (l < r) {\n            if (s.charAt(l) != s.charAt(r)) {\n                System.out.println("NO");\n                return;\n            }\n            l++;\n            r--;\n        }\n        System.out.println("YES");\n    }\n}\n```\n\n## 思路分析\n\n使用双指针，分别从字符串首尾向中间移动，逐字符比较。若遇到不同字符则直接输出 `NO`，遍历完毕没有不同则输出 `YES`。时间复杂度 O(n)。',
  987, 823,
  '[{"input":"abcba","output":"YES"},{"input":"hello","output":"NO"},{"input":"a","output":"YES"},{"input":"abba","output":"YES"},{"input":"abcd","output":"NO"}]',
  '{"timeLimit":1000,"memoryLimit":262144,"stackLimit":262144}',
  32, 18,
  2037025038146736130
),

-- ============================================================
-- 3. 斐波那契数列（简单）
-- ============================================================
(
  '斐波那契数列',
  '## 题目描述\n\n斐波那契数列定义如下：\n\n- F(1) = 1\n- F(2) = 1\n- F(n) = F(n-1) + F(n-2)，n ≥ 3\n\n给定正整数 n，求 F(n)。\n\n## 输入格式\n\n输入一行，包含一个正整数 n。\n\n## 输出格式\n\n输出一个整数，表示 F(n) 的值。\n\n## 示例\n\n**输入样例**\n\n```\n10\n```\n\n**输出样例**\n\n```\n55\n```\n\n## 数据范围\n\n- 1 ≤ n ≤ 40\n\n> 提示：F(40) = 102334155，在 int 范围内，无需取模。',
  '["数学", "递推", "动态规划"]',
  '## 参考答案（Java）\n\n```java\nimport java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int n = sc.nextInt();\n        if (n <= 2) {\n            System.out.println(1);\n            return;\n        }\n        long a = 1, b = 1;\n        for (int i = 3; i <= n; i++) {\n            long c = a + b;\n            a = b;\n            b = c;\n        }\n        System.out.println(b);\n    }\n}\n```\n\n## 思路分析\n\n迭代法（递推）：用两个变量 `a`、`b` 分别记录前两项，滚动更新即可。\n\n不要使用递归实现，否则 F(40) 需要约 2^40 次函数调用，会超时（TLE）。',
  2156, 1723,
  '[{"input":"1","output":"1"},{"input":"2","output":"1"},{"input":"10","output":"55"},{"input":"30","output":"832040"},{"input":"40","output":"102334155"}]',
  '{"timeLimit":1000,"memoryLimit":262144,"stackLimit":262144}',
  67, 41,
  2037025038146736130
),

-- ============================================================
-- 4. 爬楼梯（简单）
-- ============================================================
(
  '爬楼梯',
  '## 题目描述\n\n你正在爬一个共有 n 阶的楼梯，每次可以爬 **1 阶**或 **2 阶**。\n\n请计算有多少种不同的方案可以爬到楼顶？\n\n## 输入格式\n\n输入一行，包含一个正整数 n（楼梯总阶数）。\n\n## 输出格式\n\n输出一个整数，表示爬到楼顶的方案总数。\n\n## 示例\n\n**输入样例 1**\n\n```\n3\n```\n\n**输出样例 1**\n\n```\n3\n```\n\n> 三种方案：1+1+1、1+2、2+1\n\n**输入样例 2**\n\n```\n5\n```\n\n**输出样例 2**\n\n```\n8\n```\n\n## 数据范围\n\n- 1 ≤ n ≤ 45',
  '["动态规划", "数学"]',
  '## 参考答案（Java）\n\n```java\nimport java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int n = sc.nextInt();\n        if (n <= 2) {\n            System.out.println(n);\n            return;\n        }\n        long a = 1, b = 2;\n        for (int i = 3; i <= n; i++) {\n            long c = a + b;\n            a = b;\n            b = c;\n        }\n        System.out.println(b);\n    }\n}\n```\n\n## 思路分析\n\n设 dp[i] 表示爬到第 i 阶的方案数。\n\n状态转移方程：dp[i] = dp[i-1] + dp[i-2]\n\n边界条件：dp[1] = 1，dp[2] = 2\n\n可以发现本题实际上就是斐波那契数列（从 F(2) 开始计算）。',
  1876, 1453,
  '[{"input":"1","output":"1"},{"input":"2","output":"2"},{"input":"3","output":"3"},{"input":"5","output":"8"},{"input":"10","output":"89"},{"input":"45","output":"1836311903"}]',
  '{"timeLimit":1000,"memoryLimit":262144,"stackLimit":262144}',
  89, 56,
  2037025038146736130
),

-- ============================================================
-- 5. 有效的括号（简单）
-- ============================================================
(
  '有效的括号',
  '## 题目描述\n\n给定一个只包含字符 `(`、`)`、`[`、`]`、`{`、`}` 的字符串 S，判断括号是否有效。\n\n有效括号需满足：\n1. 每个左括号都有对应的右括号闭合；\n2. 左括号必须以正确的顺序闭合；\n3. 每个右括号都有对应的左括号。\n\n## 输入格式\n\n输入一行，为字符串 S（只含括号字符）。\n\n## 输出格式\n\n若括号有效，输出 `YES`；否则输出 `NO`。\n\n## 示例\n\n**输入样例 1**\n\n```\n()[]{}\n```\n\n**输出样例 1**\n\n```\nYES\n```\n\n**输入样例 2**\n\n```\n([)]\n```\n\n**输出样例 2**\n\n```\nNO\n```\n\n## 数据范围\n\n- 1 ≤ |S| ≤ 10^4',
  '["字符串", "栈"]',
  '## 参考答案（Java）\n\n```java\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        String s = sc.nextLine().trim();\n        Deque<Character> stack = new ArrayDeque<>();\n        for (char c : s.toCharArray()) {\n            if (c == \'(\' || c == \'[\' || c == \'{\') {\n                stack.push(c);\n            } else {\n                if (stack.isEmpty()) {\n                    System.out.println("NO");\n                    return;\n                }\n                char top = stack.pop();\n                if ((c == \')\' && top != \'(\') ||\n                    (c == \']\' && top != \'[\') ||\n                    (c == \'}\' && top != \'{\')) {\n                    System.out.println("NO");\n                    return;\n                }\n            }\n        }\n        System.out.println(stack.isEmpty() ? "YES" : "NO");\n    }\n}\n```\n\n## 思路分析\n\n使用栈：遇到左括号就压栈，遇到右括号就检查栈顶是否是对应的左括号。最后栈为空则有效。',
  1654, 1132,
  '[{"input":"()","output":"YES"},{"input":"()[]{}","output":"YES"},{"input":"(]","output":"NO"},{"input":"([)]","output":"NO"},{"input":"{[]}","output":"YES"},{"input":"(","output":"NO"}]',
  '{"timeLimit":1000,"memoryLimit":262144,"stackLimit":262144}',
  73, 48,
  2037025038146736130
),

-- ============================================================
-- 6. 二分查找（简单）
-- ============================================================
(
  '二分查找',
  '## 题目描述\n\n给定一个升序排列且**元素各不相同**的整数数组，以及一个目标值 `target`，请找出 `target` 在数组中的位置（下标从 1 开始）。\n\n如果不存在，返回 -1。\n\n## 输入格式\n\n第一行：整数 n（数组长度）。\n\n第二行：n 个升序整数，用空格分隔。\n\n第三行：目标值 target。\n\n## 输出格式\n\n若找到 target，输出其 1-based 下标；否则输出 -1。\n\n## 示例\n\n**输入样例**\n\n```\n5\n1 3 5 7 9\n5\n```\n\n**输出样例**\n\n```\n3\n```\n\n## 数据范围\n\n- 1 ≤ n ≤ 10^5\n- -10^9 ≤ 数组元素，target ≤ 10^9\n- 数组严格升序排列',
  '["数组", "二分查找"]',
  '## 参考答案（Java）\n\n```java\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int n = sc.nextInt();\n        int[] arr = new int[n];\n        for (int i = 0; i < n; i++) arr[i] = sc.nextInt();\n        int target = sc.nextInt();\n\n        int lo = 0, hi = n - 1;\n        while (lo <= hi) {\n            int mid = lo + (hi - lo) / 2;\n            if (arr[mid] == target) {\n                System.out.println(mid + 1);\n                return;\n            } else if (arr[mid] < target) {\n                lo = mid + 1;\n            } else {\n                hi = mid - 1;\n            }\n        }\n        System.out.println(-1);\n    }\n}\n```\n\n## 思路分析\n\n经典二分查找。每次将区间折半，用 `lo + (hi - lo) / 2` 计算中点可以避免整数溢出。时间复杂度 O(log n)。',
  2089, 1765,
  '[{"input":"5\\n1 3 5 7 9\\n5","output":"3"},{"input":"5\\n1 3 5 7 9\\n6","output":"-1"},{"input":"1\\n1\\n1","output":"1"},{"input":"6\\n-5 -3 0 2 7 10\\n7","output":"5"},{"input":"4\\n2 4 6 8\\n1","output":"-1"}]',
  '{"timeLimit":1000,"memoryLimit":262144,"stackLimit":262144}',
  58, 37,
  2037025038146736130
),

-- ============================================================
-- 7. 最大子数组和（中等）
-- ============================================================
(
  '最大子数组和',
  '## 题目描述\n\n给定一个整数数组 `nums`，找到一个具有最大和的**连续子数组**（子数组最少包含一个元素），返回其最大和。\n\n> 连续子数组是指数组中下标连续的若干个元素组成的子序列。\n\n## 输入格式\n\n第一行：整数 n（数组长度）。\n\n第二行：n 个整数，用空格分隔。\n\n## 输出格式\n\n输出一个整数，表示最大子数组的和。\n\n## 示例\n\n**输入样例**\n\n```\n9\n-2 1 -3 4 -1 2 1 -5 4\n```\n\n**输出样例**\n\n```\n6\n```\n\n> 最大子数组为 [4, -1, 2, 1]，其和为 6。\n\n## 数据范围\n\n- 1 ≤ n ≤ 10^5\n- -10^4 ≤ nums[i] ≤ 10^4',
  '["数组", "动态规划", "分治"]',
  '## 参考答案（Java）\n\n```java\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int n = sc.nextInt();\n        long cur = Long.MIN_VALUE, maxSum = Long.MIN_VALUE;\n        for (int i = 0; i < n; i++) {\n            long x = sc.nextLong();\n            cur = Math.max(x, cur + x);\n            maxSum = Math.max(maxSum, cur);\n        }\n        System.out.println(maxSum);\n    }\n}\n```\n\n## 思路分析\n\n**Kadane 算法（贪心/DP）**：\n\n设 `cur` 为以当前元素结尾的最大子数组和：\n- `cur = max(nums[i], cur + nums[i])`\n  - 若 `cur + nums[i]` 不如单独取 `nums[i]`，说明之前的子数组是负担，应从当前元素重新开始。\n\n`maxSum` 记录全局最大值。时间复杂度 O(n)，空间复杂度 O(1)。',
  2543, 1234,
  '[{"input":"9\\n-2 1 -3 4 -1 2 1 -5 4","output":"6"},{"input":"1\\n1","output":"1"},{"input":"5\\n5 4 -1 7 8","output":"23"},{"input":"4\\n-3 -2 -1 -4","output":"-1"},{"input":"6\\n1 -1 1 -1 1 -1","output":"1"}]',
  '{"timeLimit":1000,"memoryLimit":262144,"stackLimit":262144}',
  112, 78,
  2037025038146736130
),

-- ============================================================
-- 8. 买卖股票的最佳时机（中等）
-- ============================================================
(
  '买卖股票的最佳时机',
  '## 题目描述\n\n给定一个数组 `prices`，第 i 个元素表示某股票第 i 天的价格。\n\n你只能选择**某一天买入**这只股票，并选择在**未来的某一个不同的日子卖出**。设计一个算法来计算你所能获取的最大利润。\n\n如果无法获得任何利润，返回 0。\n\n## 输入格式\n\n第一行：整数 n（天数）。\n\n第二行：n 个整数，表示每天的股票价格。\n\n## 输出格式\n\n输出一个整数，表示可以获得的最大利润（不能获利则输出 0）。\n\n## 示例\n\n**输入样例 1**\n\n```\n6\n7 1 5 3 6 4\n```\n\n**输出样例 1**\n\n```\n5\n```\n\n> 第 2 天买入（价格 = 1），第 5 天卖出（价格 = 6），利润 = 6 - 1 = 5。\n\n**输入样例 2**\n\n```\n5\n7 6 4 3 1\n```\n\n**输出样例 2**\n\n```\n0\n```\n\n> 价格持续下跌，无法获利。\n\n## 数据范围\n\n- 1 ≤ n ≤ 10^5\n- 0 ≤ prices[i] ≤ 10^4',
  '["数组", "动态规划", "贪心"]',
  '## 参考答案（Java）\n\n```java\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner sc = new Scanner(System.in);\n        int n = sc.nextInt();\n        int minPrice = Integer.MAX_VALUE;\n        int maxProfit = 0;\n        for (int i = 0; i < n; i++) {\n            int price = sc.nextInt();\n            if (price < minPrice) {\n                minPrice = price;   // 更新历史最低价\n            } else if (price - minPrice > maxProfit) {\n                maxProfit = price - minPrice;  // 更新最大利润\n            }\n        }\n        System.out.println(maxProfit);\n    }\n}\n```\n\n## 思路分析\n\n**贪心**：一次遍历维护「历史最低价格」和「当前最大利润」。\n\n对每个价格 price：\n- 若低于历史最低价，更新最低价；\n- 否则计算以此价格卖出的利润，更新最大利润。\n\n时间复杂度 O(n)，空间复杂度 O(1)。',
  1987, 1345,
  '[{"input":"6\\n7 1 5 3 6 4","output":"5"},{"input":"5\\n7 6 4 3 1","output":"0"},{"input":"3\\n1 2 3","output":"2"},{"input":"4\\n3 1 4 2","output":"3"},{"input":"1\\n5","output":"0"}]',
  '{"timeLimit":1000,"memoryLimit":262144,"stackLimit":262144}',
  98, 65,
  2037025038146736130
);
