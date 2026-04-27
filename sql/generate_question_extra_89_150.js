const fs = require('fs');

const outPath = 'F:/ideaproject/OJ_project/sql/question_extra_89_150.sql';
const userId = '2037025038146736130';
const judgeConfig = { timeLimit: 1000, memoryLimit: 262144, stackLimit: 262144 };

function sql(s) {
  return `'${String(s)
    .replace(/\\/g, '\\\\')
    .replace(/'/g, "''")
    .replace(/\r/g, '\\r')
    .replace(/\n/g, '\\n')
    .replace(/\t/g, '\\t')}'`;
}

function examplesText(cases) {
  return cases.map((c, i) => {
    const no = cases.length > 1 ? ` ${i + 1}` : '';
    return `**输入样例${no}**\n\n\`\`\`\n${c.input}\n\`\`\`\n\n**输出样例${no}**\n\n\`\`\`\n${c.output}\n\`\`\``;
  }).join('\n\n');
}

function content(p) {
  return `## 题目描述\n\n${p.desc}\n\n## 输入格式\n\n${p.input}\n\n## 输出格式\n\n${p.output}\n\n## 示例\n\n${examplesText(p.cases)}\n\n## 数据范围\n\n${p.range}`;
}

function answer(p) {
  return `## 参考答案（Java）\n\n\`\`\`java\n${p.code.trim()}\n\`\`\`\n\n## 思路分析\n\n${p.analysis}`;
}

const problems = [];
function add(p) {
  problems.push({
    difficulty: 'easy',
    tags: ['入门', '模拟'],
    range: '- 1 ≤ n ≤ 10^5\n- -10^9 ≤ a[i] ≤ 10^9',
    ...p,
  });
}

function arrayCode(body, extraRead = '') {
  return `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
${extraRead}${body}
    }
}`;
}

add({
  title: '数组元素绝对值和',
  desc: '给定一个长度为 n 的整数数组，求所有元素绝对值之和。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出一个整数，表示绝对值之和。',
  tags: ['数组', '模拟'],
  cases: [{ input: '5\n-1 2 -3 4 0', output: '10' }, { input: '3\n-100 50 1', output: '151' }],
  code: arrayCode(`        long ans = 0;
        for (int i = 0; i < n; i++) ans += Math.abs(sc.nextLong());
        System.out.println(ans);`),
  analysis: '顺序读取每个元素，将其绝对值累加即可。元素数量和数值较大时，使用 long 保存总和。'
});

add({
  title: '数组极差',
  desc: '给定一个长度为 n 的整数数组，求数组最大值与最小值之差。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出数组的极差。',
  tags: ['数组', '模拟'],
  cases: [{ input: '5\n3 8 -2 7 1', output: '10' }, { input: '1\n6', output: '0' }],
  code: arrayCode(`        long mn = Long.MAX_VALUE, mx = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long x = sc.nextLong();
            mn = Math.min(mn, x);
            mx = Math.max(mx, x);
        }
        System.out.println(mx - mn);`),
  analysis: '一次遍历同时维护最大值和最小值，遍历结束后输出二者差值。'
});

add({
  title: '统计正数个数',
  desc: '给定 n 个整数，统计其中大于 0 的数有多少个。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出正数的个数。',
  tags: ['数组', '模拟'],
  cases: [{ input: '6\n-1 0 2 3 -5 7', output: '3' }, { input: '3\n-2 -1 0', output: '0' }],
  code: arrayCode(`        int cnt = 0;
        for (int i = 0; i < n; i++) if (sc.nextLong() > 0) cnt++;
        System.out.println(cnt);`),
  analysis: '逐个判断元素是否大于 0，满足条件就累加计数器。'
});

add({
  title: '统计负数个数',
  desc: '给定 n 个整数，统计其中小于 0 的数有多少个。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出负数的个数。',
  tags: ['数组', '模拟'],
  cases: [{ input: '5\n-3 -1 0 2 4', output: '2' }, { input: '4\n1 2 3 4', output: '0' }],
  code: arrayCode(`        int cnt = 0;
        for (int i = 0; i < n; i++) if (sc.nextLong() < 0) cnt++;
        System.out.println(cnt);`),
  analysis: '扫描数组并统计小于 0 的元素数量。'
});

add({
  title: '统计零的个数',
  desc: '给定 n 个整数，统计其中等于 0 的数有多少个。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出 0 的个数。',
  tags: ['数组', '模拟'],
  cases: [{ input: '6\n0 1 0 -2 3 0', output: '3' }, { input: '3\n1 2 3', output: '0' }],
  code: arrayCode(`        int cnt = 0;
        for (int i = 0; i < n; i++) if (sc.nextLong() == 0) cnt++;
        System.out.println(cnt);`),
  analysis: '遍历所有元素，判断是否等于 0，满足则计数加一。'
});

add({
  title: '数组交替和',
  desc: '给定长度为 n 的数组 a，计算 a1 - a2 + a3 - a4 + ... 的结果。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出交替和。',
  tags: ['数组', '模拟'],
  cases: [{ input: '5\n1 2 3 4 5', output: '3' }, { input: '4\n10 1 2 3', output: '8' }],
  code: arrayCode(`        long ans = 0;
        for (int i = 0; i < n; i++) {
            long x = sc.nextLong();
            ans += (i % 2 == 0 ? x : -x);
        }
        System.out.println(ans);`),
  analysis: '按下标奇偶决定当前元素是加还是减。题目中的位置从 1 开始，对应 Java 下标偶数为加号。'
});

add({
  title: '数组是否全为正数',
  desc: '给定 n 个整数，判断它们是否全部大于 0。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '若全部为正数输出 YES，否则输出 NO。',
  tags: ['数组', '模拟'],
  cases: [{ input: '4\n1 2 3 4', output: 'YES' }, { input: '3\n1 0 2', output: 'NO' }],
  code: arrayCode(`        boolean ok = true;
        for (int i = 0; i < n; i++) if (sc.nextLong() <= 0) ok = false;
        System.out.println(ok ? "YES" : "NO");`),
  analysis: '只要存在一个小于等于 0 的元素，答案就是 NO；否则所有元素都为正数。'
});

add({
  title: '数组是否严格递增',
  desc: '给定一个整数数组，判断每个元素是否都严格大于前一个元素。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '若数组严格递增输出 YES，否则输出 NO。',
  tags: ['数组', '模拟'],
  cases: [{ input: '5\n1 2 3 7 9', output: 'YES' }, { input: '4\n1 2 2 3', output: 'NO' }],
  code: arrayCode(`        long prev = Long.MIN_VALUE;
        boolean ok = true;
        for (int i = 0; i < n; i++) {
            long x = sc.nextLong();
            if (i > 0 && x <= prev) ok = false;
            prev = x;
        }
        System.out.println(ok ? "YES" : "NO");`),
  analysis: '从左到右比较相邻元素，只要出现 a[i] <= a[i-1] 就不是严格递增。'
});

add({
  title: '相邻相等对数',
  desc: '给定一个长度为 n 的数组，统计满足 a[i] = a[i+1] 的相邻位置对数量。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出相邻相等对数。',
  tags: ['数组', '模拟'],
  cases: [{ input: '6\n1 1 2 2 2 3', output: '3' }, { input: '3\n1 2 3', output: '0' }],
  code: arrayCode(`        long prev = 0;
        int ans = 0;
        for (int i = 0; i < n; i++) {
            long x = sc.nextLong();
            if (i > 0 && x == prev) ans++;
            prev = x;
        }
        System.out.println(ans);`),
  analysis: '扫描数组时保存前一个元素，与当前元素比较，相等则计数加一。'
});

add({
  title: '数组中大于 X 的个数',
  desc: '给定 n 个整数和一个整数 x，统计数组中大于 x 的元素个数。',
  input: '第一行输入整数 n 和 x。\n\n第二行输入 n 个整数。',
  output: '输出大于 x 的元素个数。',
  tags: ['数组', '模拟'],
  cases: [{ input: '5 3\n1 4 3 5 9', output: '3' }, { input: '4 10\n1 2 3 4', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long x = sc.nextLong();
        int cnt = 0;
        for (int i = 0; i < n; i++) if (sc.nextLong() > x) cnt++;
        System.out.println(cnt);
    }
}`,
  analysis: '读取阈值 x 后遍历数组，逐个比较是否大于 x。'
});

add({
  title: '数组中 X 的首次位置',
  desc: '给定 n 个整数和一个整数 x，找出 x 在数组中第一次出现的位置。位置从 1 开始，若不存在输出 -1。',
  input: '第一行输入整数 n 和 x。\n\n第二行输入 n 个整数。',
  output: '输出 x 首次出现的位置，若不存在输出 -1。',
  tags: ['数组', '查找'],
  cases: [{ input: '6 4\n1 4 2 4 5 4', output: '2' }, { input: '3 9\n1 2 3', output: '-1' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long x = sc.nextLong();
        int ans = -1;
        for (int i = 1; i <= n; i++) {
            long v = sc.nextLong();
            if (v == x && ans == -1) ans = i;
        }
        System.out.println(ans);
    }
}`,
  analysis: '按顺序扫描数组，第一次遇到等于 x 的元素时记录位置，之后保持不变。'
});

add({
  title: '数组中 X 的最后位置',
  desc: '给定 n 个整数和一个整数 x，找出 x 在数组中最后一次出现的位置。位置从 1 开始，若不存在输出 -1。',
  input: '第一行输入整数 n 和 x。\n\n第二行输入 n 个整数。',
  output: '输出 x 最后出现的位置，若不存在输出 -1。',
  tags: ['数组', '查找'],
  cases: [{ input: '6 4\n1 4 2 4 5 4', output: '6' }, { input: '3 9\n1 2 3', output: '-1' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long x = sc.nextLong();
        int ans = -1;
        for (int i = 1; i <= n; i++) if (sc.nextLong() == x) ans = i;
        System.out.println(ans);
    }
}`,
  analysis: '从左到右扫描，每次遇到 x 都更新位置，最终保留下来的就是最后一次出现位置。'
});

add({
  title: '前缀最大值',
  desc: '给定长度为 n 的数组，输出每个前缀中的最大值。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出 n 个整数，第 i 个表示前 i 个元素的最大值。',
  tags: ['数组', '前缀'],
  cases: [{ input: '5\n1 3 2 5 4', output: '1 3 3 5 5' }, { input: '3\n-1 -5 0', output: '-1 -1 0' }],
  code: arrayCode(`        long mx = Long.MIN_VALUE;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            mx = Math.max(mx, sc.nextLong());
            if (i > 0) sb.append(' ');
            sb.append(mx);
        }
        System.out.println(sb);`),
  analysis: '维护当前已经读到的最大值，每读入一个元素就更新并输出当前最大值。'
});

add({
  title: '后缀最小值',
  desc: '给定长度为 n 的数组，输出每个后缀中的最小值。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出 n 个整数，第 i 个表示从第 i 个元素到末尾的最小值。',
  tags: ['数组', '后缀'],
  cases: [{ input: '5\n5 3 4 2 6', output: '2 2 2 2 6' }, { input: '3\n1 2 3', output: '1 2 3' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long[] a = new long[n], suf = new long[n];
        for (int i = 0; i < n; i++) a[i] = sc.nextLong();
        for (int i = n - 1; i >= 0; i--) suf[i] = (i == n - 1) ? a[i] : Math.min(a[i], suf[i + 1]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(suf[i]);
        }
        System.out.println(sb);
    }
}`,
  analysis: '从右向左维护当前后缀最小值，suf[i] = min(a[i], suf[i+1])。'
});

add({
  title: '数组去掉最大最小后的平均值',
  desc: '给定 n 个整数，去掉一个最大值和一个最小值后，求剩余元素的整数平均值。结果向下取整。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出去掉最大最小后的平均值。',
  tags: ['数组', '模拟'],
  range: '- 3 ≤ n ≤ 10^5\n- 0 ≤ a[i] ≤ 10^9',
  cases: [{ input: '5\n1 2 3 4 100', output: '3' }, { input: '3\n10 20 30', output: '20' }],
  code: arrayCode(`        long sum = 0, mn = Long.MAX_VALUE, mx = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long x = sc.nextLong();
            sum += x;
            mn = Math.min(mn, x);
            mx = Math.max(mx, x);
        }
        System.out.println((sum - mn - mx) / (n - 2));`),
  analysis: '遍历时同时求总和、最大值和最小值，最后从总和中减去一份最大值和一份最小值再除以剩余数量。'
});

add({
  title: '字符串逆序输出',
  desc: '给定一个字符串，输出它反转后的结果。',
  input: '输入一行字符串。',
  output: '输出反转后的字符串。',
  tags: ['字符串', '模拟'],
  range: '- 0 ≤ 字符串长度 ≤ 10^5',
  cases: [{ input: 'hello', output: 'olleh' }, { input: 'OJ123', output: '321JO' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.hasNextLine() ? sc.nextLine() : "";
        System.out.println(new StringBuilder(s).reverse());
    }
}`,
  analysis: '可以使用 StringBuilder 的 reverse 方法，也可以从字符串末尾向前逐字符输出。'
});

add({
  title: '统计元音字母',
  desc: '给定一行字符串，统计其中英文元音字母 a、e、i、o、u 的出现次数，大小写都算。',
  input: '输入一行字符串。',
  output: '输出元音字母总数。',
  tags: ['字符串', '模拟'],
  range: '- 0 ≤ 字符串长度 ≤ 10^5',
  cases: [{ input: 'Hello World', output: '3' }, { input: 'BCDFG', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.hasNextLine() ? sc.nextLine() : "";
        int ans = 0;
        for (char c : s.toLowerCase().toCharArray()) if ("aeiou".indexOf(c) >= 0) ans++;
        System.out.println(ans);
    }
}`,
  analysis: '先统一转成小写，再判断字符是否属于元音集合。'
});

add({
  title: '删除字符串空格',
  desc: '给定一行字符串，删除其中所有空格字符后输出。',
  input: '输入一行字符串。',
  output: '输出删除空格后的字符串。',
  tags: ['字符串', '模拟'],
  range: '- 0 ≤ 字符串长度 ≤ 10^5',
  cases: [{ input: 'a b c d', output: 'abcd' }, { input: ' no space ', output: 'nospace' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.hasNextLine() ? sc.nextLine() : "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (c != ' ') sb.append(c);
        System.out.println(sb);
    }
}`,
  analysis: '遍历字符串，只保留不是空格的字符。'
});

add({
  title: '数字字符求和',
  desc: '给定一行字符串，求其中所有数字字符表示的数值之和。',
  input: '输入一行字符串。',
  output: '输出数字字符之和。',
  tags: ['字符串', '模拟'],
  range: '- 0 ≤ 字符串长度 ≤ 10^5',
  cases: [{ input: 'a1b2c3', output: '6' }, { input: 'no digits', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.hasNextLine() ? sc.nextLine() : "";
        int ans = 0;
        for (char c : s.toCharArray()) if (Character.isDigit(c)) ans += c - '0';
        System.out.println(ans);
    }
}`,
  analysis: '使用 Character.isDigit 判断数字字符，并用 c - \'0\' 转成对应数值。'
});

add({
  title: '统计大写字母',
  desc: '给定一行字符串，统计其中大写英文字母的个数。',
  input: '输入一行字符串。',
  output: '输出大写字母个数。',
  tags: ['字符串', '模拟'],
  range: '- 0 ≤ 字符串长度 ≤ 10^5',
  cases: [{ input: 'Hello OJ', output: '3' }, { input: 'abc123', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.hasNextLine() ? sc.nextLine() : "";
        int ans = 0;
        for (char c : s.toCharArray()) if (c >= 'A' && c <= 'Z') ans++;
        System.out.println(ans);
    }
}`,
  analysis: '大写英文字母的 ASCII 范围是 A 到 Z，逐字符判断即可。'
});

add({
  title: '最长单词长度',
  desc: '给定一行英文文本，单词由一个或多个空格分隔，求最长单词长度。',
  input: '输入一行字符串。',
  output: '输出最长单词长度。若没有单词，输出 0。',
  tags: ['字符串', '模拟'],
  range: '- 0 ≤ 字符串长度 ≤ 10^5',
  cases: [{ input: 'hello world from oj', output: '5' }, { input: '   ', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.hasNextLine() ? sc.nextLine().trim() : "";
        if (s.isEmpty()) { System.out.println(0); return; }
        int ans = 0;
        for (String w : s.split("\\\\s+")) ans = Math.max(ans, w.length());
        System.out.println(ans);
    }
}`,
  analysis: '先去掉首尾空格，空串直接输出 0；否则按连续空白分隔并取最大长度。'
});

add({
  title: '字符首次出现位置',
  desc: '给定字符串 s 和字符 c，输出 c 在 s 中第一次出现的位置，位置从 1 开始。若不存在输出 -1。',
  input: '第一行输入字符串 s。\n\n第二行输入一个字符 c。',
  output: '输出首次出现位置。',
  tags: ['字符串', '查找'],
  range: '- 1 ≤ |s| ≤ 10^5',
  cases: [{ input: 'banana\na', output: '2' }, { input: 'abc\nz', output: '-1' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine();
        char c = sc.nextLine().charAt(0);
        int idx = s.indexOf(c);
        System.out.println(idx < 0 ? -1 : idx + 1);
    }
}`,
  analysis: '可以调用 indexOf 查找字符第一次出现的下标，题目要求位置从 1 开始，所以答案要加 1。'
});

add({
  title: '只含二进制字符判断',
  desc: '给定一个字符串，判断它是否只由字符 0 和 1 组成。',
  input: '输入一行字符串。',
  output: '若只包含 0 和 1 输出 YES，否则输出 NO。',
  tags: ['字符串', '模拟'],
  range: '- 1 ≤ 字符串长度 ≤ 10^5',
  cases: [{ input: '101001', output: 'YES' }, { input: '10201', output: 'NO' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine().trim();
        for (char c : s.toCharArray()) {
            if (c != '0' && c != '1') { System.out.println("NO"); return; }
        }
        System.out.println("YES");
    }
}`,
  analysis: '遍历字符串，只要发现某个字符既不是 0 也不是 1，就可以直接判定为 NO。'
});

add({
  title: '压缩字符串长度',
  desc: '给定一个字符串，按连续相同字符段压缩为字符加次数的形式，例如 aaabb 变成 a3b2。输出压缩后字符串长度。',
  input: '输入一行字符串 s。',
  output: '输出压缩后字符串长度。',
  tags: ['字符串', '模拟'],
  range: '- 1 ≤ |s| ≤ 10^5\n- s 由小写字母组成',
  cases: [{ input: 'aaabb', output: '4' }, { input: 'abc', output: '6' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine().trim();
        int ans = 0, cnt = 1;
        for (int i = 1; i <= s.length(); i++) {
            if (i < s.length() && s.charAt(i) == s.charAt(i - 1)) cnt++;
            else {
                ans += 1 + String.valueOf(cnt).length();
                cnt = 1;
            }
        }
        System.out.println(ans);
    }
}`,
  analysis: '扫描连续相同字符段，每段压缩后贡献一个字符和该段长度的十进制位数。'
});

add({
  title: '矩阵转置输出',
  desc: '给定一个 n 行 m 列矩阵，输出它的转置矩阵。',
  input: '第一行输入 n 和 m。\n\n接下来 n 行，每行 m 个整数。',
  output: '输出 m 行 n 列的转置矩阵。',
  tags: ['数组', '矩阵'],
  range: '- 1 ≤ n,m ≤ 100',
  cases: [{ input: '2 3\n1 2 3\n4 5 6', output: '1 4\n2 5\n3 6' }, { input: '1 2\n7 8', output: '7\n8' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), m = sc.nextInt();
        int[][] a = new int[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) a[i][j] = sc.nextInt();
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < m; j++) {
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(' ');
                sb.append(a[i][j]);
            }
            if (j + 1 < m) sb.append('\\n');
        }
        System.out.println(sb);
    }
}`,
  analysis: '转置后第 j 行第 i 列来自原矩阵第 i 行第 j 列，因此按原矩阵列优先输出即可。'
});

add({
  title: '矩阵主对角线和',
  desc: '给定一个 n 阶方阵，求主对角线元素之和。',
  input: '第一行输入整数 n。\n\n接下来 n 行，每行 n 个整数。',
  output: '输出主对角线元素和。',
  tags: ['数组', '矩阵'],
  range: '- 1 ≤ n ≤ 100',
  cases: [{ input: '3\n1 2 3\n4 5 6\n7 8 9', output: '15' }, { input: '2\n1 2\n3 4', output: '5' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long ans = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            long x = sc.nextLong();
            if (i == j) ans += x;
        }
        System.out.println(ans);
    }
}`,
  analysis: '主对角线元素满足行号等于列号，遍历矩阵时累加这些位置即可。'
});

add({
  title: '矩阵每行最大值',
  desc: '给定一个 n 行 m 列矩阵，输出每一行的最大值。',
  input: '第一行输入 n 和 m。\n\n接下来 n 行，每行 m 个整数。',
  output: '输出 n 个整数，表示每行最大值，每个占一行。',
  tags: ['数组', '矩阵'],
  range: '- 1 ≤ n,m ≤ 100',
  cases: [{ input: '2 3\n1 5 2\n-1 -3 -2', output: '5\n-1' }, { input: '1 2\n7 8', output: '8' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), m = sc.nextInt();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            long mx = Long.MIN_VALUE;
            for (int j = 0; j < m; j++) mx = Math.max(mx, sc.nextLong());
            sb.append(mx).append('\\n');
        }
        System.out.print(sb);
    }
}`,
  analysis: '逐行处理，每一行内维护最大值，读完一行后输出该行最大值。'
});

add({
  title: '矩阵每列最小值',
  desc: '给定一个 n 行 m 列矩阵，输出每一列的最小值。',
  input: '第一行输入 n 和 m。\n\n接下来 n 行，每行 m 个整数。',
  output: '输出 m 个整数，表示每列最小值。',
  tags: ['数组', '矩阵'],
  range: '- 1 ≤ n,m ≤ 100',
  cases: [{ input: '2 3\n1 5 2\n-1 7 0', output: '-1 5 0' }, { input: '1 2\n7 8', output: '7 8' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), m = sc.nextInt();
        long[] mn = new long[m];
        Arrays.fill(mn, Long.MAX_VALUE);
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) mn[j] = Math.min(mn[j], sc.nextLong());
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < m; j++) {
            if (j > 0) sb.append(' ');
            sb.append(mn[j]);
        }
        System.out.println(sb);
    }
}`,
  analysis: '用数组 mn[j] 维护第 j 列当前最小值，读入矩阵时逐列更新。'
});

add({
  title: '两个矩阵是否相等',
  desc: '给定两个 n 行 m 列矩阵，判断它们对应位置的元素是否完全相同。',
  input: '第一行输入 n 和 m。\n\n接下来 n 行输入矩阵 A。\n\n再接下来 n 行输入矩阵 B。',
  output: '若两个矩阵相等输出 YES，否则输出 NO。',
  tags: ['数组', '矩阵'],
  range: '- 1 ≤ n,m ≤ 100',
  cases: [{ input: '2 2\n1 2\n3 4\n1 2\n3 4', output: 'YES' }, { input: '1 2\n1 2\n2 1', output: 'NO' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), m = sc.nextInt();
        int[][] a = new int[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) a[i][j] = sc.nextInt();
        boolean ok = true;
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) if (a[i][j] != sc.nextInt()) ok = false;
        System.out.println(ok ? "YES" : "NO");
    }
}`,
  analysis: '先保存第一个矩阵，再读第二个矩阵时逐个位置比较，只要有一个不同就不相等。'
});

add({
  title: '数字各位之和',
  desc: '给定一个非负整数 n，求它各个十进制数位之和。',
  input: '输入一个非负整数 n。',
  output: '输出各位数字之和。',
  tags: ['数学', '模拟'],
  range: '- 0 ≤ n ≤ 10^18',
  cases: [{ input: '12345', output: '15' }, { input: '0', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine().trim();
        int ans = 0;
        for (char c : s.toCharArray()) ans += c - '0';
        System.out.println(ans);
    }
}`,
  analysis: '把整数当作字符串读取，逐个字符转为数字并累加，可以避免整数范围带来的细节问题。'
});

add({
  title: '数字根',
  desc: '给定一个非负整数，反复计算各位数字之和，直到结果只剩一位，输出这个一位数。',
  input: '输入一个非负整数 n。',
  output: '输出 n 的数字根。',
  tags: ['数学', '模拟'],
  range: '- 0 ≤ n ≤ 10^100000',
  cases: [{ input: '38', output: '2' }, { input: '0', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine().trim();
        int mod = 0;
        for (char c : s.toCharArray()) mod = (mod + c - '0') % 9;
        if (s.equals("0")) System.out.println(0);
        else System.out.println(mod == 0 ? 9 : mod);
    }
}`,
  analysis: '数字根与模 9 相关：正整数的数字根等于 n mod 9，余数为 0 时数字根为 9。'
});

add({
  title: '最小公倍数',
  desc: '给定两个正整数 a 和 b，求它们的最小公倍数。',
  input: '输入两个正整数 a 和 b。',
  output: '输出 a 和 b 的最小公倍数。',
  tags: ['数学', '数论'],
  range: '- 1 ≤ a,b ≤ 10^9',
  cases: [{ input: '12 18', output: '36' }, { input: '7 5', output: '35' }],
  code: `
import java.util.*;

public class Main {
    static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long a = sc.nextLong(), b = sc.nextLong();
        System.out.println(a / gcd(a, b) * b);
    }
}`,
  analysis: '利用公式 lcm(a,b)=a/gcd(a,b)*b。先除再乘可以减少溢出的风险。'
});

add({
  title: '数组最大公约数',
  desc: '给定 n 个正整数，求它们的最大公约数。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个正整数。',
  output: '输出这 n 个数的最大公约数。',
  tags: ['数学', '数论'],
  range: '- 1 ≤ n ≤ 10^5\n- 1 ≤ a[i] ≤ 10^9',
  cases: [{ input: '4\n12 18 30 42', output: '6' }, { input: '3\n7 11 13', output: '1' }],
  code: `
import java.util.*;

public class Main {
    static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        long ans = 0;
        for (int i = 0; i < n; i++) ans = gcd(ans, sc.nextLong());
        System.out.println(ans);
    }
}`,
  analysis: '最大公约数满足结合律，可以从左到右把当前答案与下一个数求 gcd。'
});

add({
  title: '快速幂取模',
  desc: '给定 a、b、mod，计算 a^b mod mod。',
  input: '输入三个整数 a、b、mod。',
  output: '输出 a^b 对 mod 取模的结果。',
  tags: ['数学', '快速幂'],
  range: '- 0 ≤ a,b ≤ 10^18\n- 1 ≤ mod ≤ 10^9',
  cases: [{ input: '2 10 1000', output: '24' }, { input: '3 0 7', output: '1' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long a = sc.nextLong(), b = sc.nextLong(), mod = sc.nextLong();
        long ans = 1 % mod;
        a %= mod;
        while (b > 0) {
            if ((b & 1) == 1) ans = ans * a % mod;
            a = a * a % mod;
            b >>= 1;
        }
        System.out.println(ans);
    }
}`,
  analysis: '二进制快速幂把指数拆成若干个 2 的幂。每次根据最低位决定是否乘入答案，然后底数平方、指数右移。'
});

add({
  title: '约数个数',
  desc: '给定正整数 n，求它的正约数个数。',
  input: '输入一个正整数 n。',
  output: '输出 n 的正约数个数。',
  tags: ['数学', '数论'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^12',
  cases: [{ input: '12', output: '6' }, { input: '13', output: '2' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long n = sc.nextLong(), ans = 1;
        for (long p = 2; p * p <= n; p++) {
            if (n % p == 0) {
                int cnt = 0;
                while (n % p == 0) { n /= p; cnt++; }
                ans *= cnt + 1;
            }
        }
        if (n > 1) ans *= 2;
        System.out.println(ans);
    }
}`,
  analysis: '把 n 分解为质因数乘积。若 n=p1^a1*p2^a2...，则约数个数为 (a1+1)(a2+1)...。'
});

add({
  title: '质因数分解',
  desc: '给定正整数 n，按从小到大的顺序输出它的所有质因数。若某个质因数出现多次，需要重复输出。',
  input: '输入一个正整数 n。',
  output: '输出 n 的质因数，之间用空格分隔。若 n=1，输出 1。',
  tags: ['数学', '数论'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^12',
  cases: [{ input: '60', output: '2 2 3 5' }, { input: '13', output: '13' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long n = sc.nextLong();
        if (n == 1) { System.out.println(1); return; }
        StringBuilder sb = new StringBuilder();
        for (long p = 2; p * p <= n; p++) {
            while (n % p == 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(p);
                n /= p;
            }
        }
        if (n > 1) { if (sb.length() > 0) sb.append(' '); sb.append(n); }
        System.out.println(sb);
    }
}`,
  analysis: '从小到大试除。每当 p 能整除 n，就输出 p 并把 n 除以 p；循环结束后若 n>1，它本身是最后的质因数。'
});

add({
  title: '大整数加法',
  desc: '给定两个非负整数，它们可能非常大，求它们的和。',
  input: '输入两行，每行一个非负整数。',
  output: '输出两个整数之和。',
  tags: ['字符串', '高精度'],
  difficulty: 'medium',
  range: '- 1 ≤ 数字长度 ≤ 10^5',
  cases: [{ input: '123456789123456789\n987654321', output: '123456790111111110' }, { input: '0\n0', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String a = sc.nextLine().trim(), b = sc.nextLine().trim();
        int i = a.length() - 1, j = b.length() - 1, carry = 0;
        StringBuilder sb = new StringBuilder();
        while (i >= 0 || j >= 0 || carry > 0) {
            int sum = carry;
            if (i >= 0) sum += a.charAt(i--) - '0';
            if (j >= 0) sum += b.charAt(j--) - '0';
            sb.append(sum % 10);
            carry = sum / 10;
        }
        System.out.println(sb.reverse());
    }
}`,
  analysis: '模拟竖式加法，从低位到高位逐位相加并维护进位，最后反转得到结果。'
});

add({
  title: '日期是第几天',
  desc: '给定一个合法日期，计算它是这一年的第几天。',
  input: '输入三个整数 year month day。',
  output: '输出该日期是当年的第几天。',
  tags: ['数学', '模拟'],
  range: '- 1 ≤ year ≤ 9999\n- 日期合法',
  cases: [{ input: '2024 3 1', output: '61' }, { input: '2023 12 31', output: '365' }],
  code: `
import java.util.*;

public class Main {
    static boolean leap(int y) { return y % 400 == 0 || (y % 4 == 0 && y % 100 != 0); }
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int y = sc.nextInt(), m = sc.nextInt(), d = sc.nextInt();
        int[] days = {0,31,28,31,30,31,30,31,31,30,31,30,31};
        if (leap(y)) days[2] = 29;
        int ans = d;
        for (int i = 1; i < m; i++) ans += days[i];
        System.out.println(ans);
    }
}`,
  analysis: '先根据闰年规则确定二月天数，再累加目标月份之前所有月份的天数和当天日期。'
});

add({
  title: '二维前缀和查询',
  desc: '给定一个 n 行 m 列矩阵，回答 q 次子矩阵和查询。',
  input: '第一行输入 n、m、q。\n\n接下来 n 行输入矩阵。\n\n接下来 q 行，每行输入 x1 y1 x2 y2，表示左上角和右下角坐标，坐标从 1 开始。',
  output: '对每个查询输出一行子矩阵元素和。',
  tags: ['前缀和', '矩阵'],
  difficulty: 'medium',
  range: '- 1 ≤ n,m ≤ 500\n- 1 ≤ q ≤ 10^5',
  cases: [{ input: '3 3 2\n1 2 3\n4 5 6\n7 8 9\n1 1 2 2\n2 2 3 3', output: '12\n28' }, { input: '1 1 1\n5\n1 1 1 1', output: '5' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), m = sc.nextInt(), q = sc.nextInt();
        long[][] pre = new long[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                pre[i][j] = sc.nextLong() + pre[i - 1][j] + pre[i][j - 1] - pre[i - 1][j - 1];
            }
        }
        StringBuilder sb = new StringBuilder();
        while (q-- > 0) {
            int x1 = sc.nextInt(), y1 = sc.nextInt(), x2 = sc.nextInt(), y2 = sc.nextInt();
            long ans = pre[x2][y2] - pre[x1 - 1][y2] - pre[x2][y1 - 1] + pre[x1 - 1][y1 - 1];
            sb.append(ans).append('\\n');
        }
        System.out.print(sb);
    }
}`,
  analysis: '二维前缀和 pre[i][j] 表示从 (1,1) 到 (i,j) 的矩形和。查询时用容斥公式 O(1) 得到子矩阵和。'
});

add({
  title: '差分数组区间加',
  desc: '给定长度为 n 的初始全 0 数组，进行 q 次区间加法操作，输出最终数组。',
  input: '第一行输入 n 和 q。\n\n接下来 q 行，每行输入 l r x，表示区间 [l,r] 每个元素加 x。',
  output: '输出最终数组，元素之间用空格分隔。',
  tags: ['差分', '数组'],
  difficulty: 'medium',
  range: '- 1 ≤ n,q ≤ 10^5\n- -10^9 ≤ x ≤ 10^9',
  cases: [{ input: '5 3\n1 3 2\n2 5 1\n4 4 3', output: '2 3 3 4 1' }, { input: '3 1\n1 3 -2', output: '-2 -2 -2' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), q = sc.nextInt();
        long[] diff = new long[n + 2];
        for (int i = 0; i < q; i++) {
            int l = sc.nextInt(), r = sc.nextInt();
            long x = sc.nextLong();
            diff[l] += x;
            diff[r + 1] -= x;
        }
        StringBuilder sb = new StringBuilder();
        long cur = 0;
        for (int i = 1; i <= n; i++) {
            cur += diff[i];
            if (i > 1) sb.append(' ');
            sb.append(cur);
        }
        System.out.println(sb);
    }
}`,
  analysis: '差分数组支持区间加：diff[l]+=x，diff[r+1]-=x。最后求前缀和即可还原每个位置的值。'
});

add({
  title: '最近更小元素',
  desc: '给定数组 a，对于每个位置 i，输出它左侧距离最近且严格小于 a[i] 的元素；若不存在输出 -1。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数。',
  output: '输出 n 个整数，表示每个位置的最近更小元素。',
  tags: ['单调栈', '数组'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^5',
  cases: [{ input: '5\n2 1 5 3 4', output: '-1 -1 1 1 3' }, { input: '3\n1 2 3', output: '-1 1 2' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        Deque<Integer> st = new ArrayDeque<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int x = sc.nextInt();
            while (!st.isEmpty() && st.peek() >= x) st.pop();
            if (i > 0) sb.append(' ');
            sb.append(st.isEmpty() ? -1 : st.peek());
            st.push(x);
        }
        System.out.println(sb);
    }
}`,
  analysis: '维护单调递增栈。当前元素会弹出所有不小于它的元素，弹出后栈顶就是最近的更小元素。'
});

add({
  title: '逆波兰表达式求值',
  desc: '给定一个逆波兰表达式，计算它的值。表达式只包含整数和 +、-、*、/ 四种运算符，除法向零截断。',
  input: '第一行输入整数 n，表示 token 数。\n\n第二行输入 n 个 token。',
  output: '输出表达式的计算结果。',
  tags: ['栈', '表达式'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^4\n- 中间结果在 int 范围内',
  cases: [{ input: '5\n2 1 + 3 *', output: '9' }, { input: '5\n4 13 5 / +', output: '6' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        Deque<Integer> st = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            String t = sc.next();
            if ("+-*/".contains(t) && t.length() == 1) {
                int b = st.pop(), a = st.pop();
                if (t.equals("+")) st.push(a + b);
                else if (t.equals("-")) st.push(a - b);
                else if (t.equals("*")) st.push(a * b);
                else st.push(a / b);
            } else {
                st.push(Integer.parseInt(t));
            }
        }
        System.out.println(st.pop());
    }
}`,
  analysis: '逆波兰表达式用栈求值：遇到数字入栈，遇到运算符弹出两个操作数计算，再把结果压回栈。'
});

add({
  title: '迷宫最短路',
  desc: '给定 n×m 的迷宫，0 表示可走，1 表示障碍。从左上角走到右下角，每次只能上下左右移动一步，求最短步数。不可达输出 -1。',
  input: '第一行输入 n 和 m。\n\n接下来 n 行，每行 m 个整数 0 或 1。',
  output: '输出最短步数，起点到起点步数为 0。',
  tags: ['广度优先搜索', '矩阵'],
  difficulty: 'medium',
  range: '- 1 ≤ n,m ≤ 500',
  cases: [{ input: '3 3\n0 0 1\n1 0 0\n1 0 0', output: '4' }, { input: '2 2\n0 1\n1 0', output: '-1' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), m = sc.nextInt();
        int[][] g = new int[n][m], dist = new int[n][m];
        for (int[] row : dist) Arrays.fill(row, -1);
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) g[i][j] = sc.nextInt();
        if (g[0][0] == 1 || g[n - 1][m - 1] == 1) { System.out.println(-1); return; }
        int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};
        Queue<int[]> q = new LinkedList<>();
        q.offer(new int[]{0, 0}); dist[0][0] = 0;
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            for (int k = 0; k < 4; k++) {
                int x = cur[0] + dx[k], y = cur[1] + dy[k];
                if (x >= 0 && x < n && y >= 0 && y < m && g[x][y] == 0 && dist[x][y] == -1) {
                    dist[x][y] = dist[cur[0]][cur[1]] + 1;
                    q.offer(new int[]{x, y});
                }
            }
        }
        System.out.println(dist[n - 1][m - 1]);
    }
}`,
  analysis: '无权网格最短路使用 BFS。第一次到达某个格子的距离就是从起点到该格子的最短距离。'
});

add({
  title: '岛屿最大面积',
  desc: '给定只包含 0 和 1 的网格，1 表示陆地，0 表示水。上下左右相连的陆地构成岛屿，求最大岛屿面积。',
  input: '第一行输入 n 和 m。\n\n接下来 n 行，每行 m 个整数 0 或 1。',
  output: '输出最大岛屿面积。若没有岛屿，输出 0。',
  tags: ['深度优先搜索', '矩阵'],
  difficulty: 'medium',
  range: '- 1 ≤ n,m ≤ 500',
  cases: [{ input: '3 4\n1 1 0 0\n0 1 0 1\n0 0 1 1', output: '3' }, { input: '2 2\n0 0\n0 0', output: '0' }],
  code: `
import java.util.*;

public class Main {
    static int n, m;
    static int[][] g;
    static int[] dx = {1, -1, 0, 0}, dy = {0, 0, 1, -1};
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        n = sc.nextInt(); m = sc.nextInt();
        g = new int[n][m];
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) g[i][j] = sc.nextInt();
        int ans = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) if (g[i][j] == 1) ans = Math.max(ans, dfs(i, j));
        System.out.println(ans);
    }
    static int dfs(int x, int y) {
        g[x][y] = 0;
        int area = 1;
        for (int k = 0; k < 4; k++) {
            int nx = x + dx[k], ny = y + dy[k];
            if (nx >= 0 && nx < n && ny >= 0 && ny < m && g[nx][ny] == 1) area += dfs(nx, ny);
        }
        return area;
    }
}`,
  analysis: '从每块未访问陆地开始 DFS，将访问过的陆地置为 0，并统计连通块大小，取最大值。'
});

add({
  title: '并查集连通查询',
  desc: '给定 n 个点和若干操作，支持合并两个点所在集合，以及查询两个点是否连通。',
  input: '第一行输入 n 和 q。\n\n接下来 q 行，每行输入 op a b。op=1 表示合并 a 和 b，op=2 表示查询 a 和 b 是否连通。',
  output: '对每个查询输出 YES 或 NO。',
  tags: ['并查集', '图'],
  difficulty: 'medium',
  range: '- 1 ≤ n,q ≤ 10^5',
  cases: [{ input: '5 5\n1 1 2\n2 1 3\n1 2 3\n2 1 3\n2 4 5', output: 'NO\nYES\nNO' }, { input: '2 1\n2 1 2', output: 'NO' }],
  code: `
import java.util.*;

public class Main {
    static int[] parent;
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), q = sc.nextInt();
        parent = new int[n + 1];
        for (int i = 1; i <= n; i++) parent[i] = i;
        StringBuilder sb = new StringBuilder();
        while (q-- > 0) {
            int op = sc.nextInt(), a = sc.nextInt(), b = sc.nextInt();
            if (op == 1) parent[find(a)] = find(b);
            else sb.append(find(a) == find(b) ? "YES" : "NO").append('\\n');
        }
        System.out.print(sb);
    }
    static int find(int x) { return parent[x] == x ? x : (parent[x] = find(parent[x])); }
}`,
  analysis: '并查集用 parent 表示集合代表。find 时路径压缩，合并时把一个集合根节点指向另一个集合根节点。'
});

add({
  title: '最小花费爬楼梯',
  desc: '给定数组 cost，其中 cost[i] 是踩到第 i 阶的花费。每次可以爬 1 或 2 阶，可以从第 0 或第 1 阶开始，求到达楼顶的最小花费。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数 cost。',
  output: '输出最小花费。',
  tags: ['动态规划'],
  range: '- 2 ≤ n ≤ 10^5\n- 0 ≤ cost[i] ≤ 10^4',
  cases: [{ input: '3\n10 15 20', output: '15' }, { input: '10\n1 100 1 1 1 100 1 1 100 1', output: '6' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[] cost = new int[n];
        for (int i = 0; i < n; i++) cost[i] = sc.nextInt();
        int a = 0, b = 0;
        for (int i = 2; i <= n; i++) {
            int c = Math.min(b + cost[i - 1], a + cost[i - 2]);
            a = b; b = c;
        }
        System.out.println(b);
    }
}`,
  analysis: '设 dp[i] 为到达第 i 阶的最小花费，楼顶是第 n 阶。转移为 dp[i]=min(dp[i-1]+cost[i-1], dp[i-2]+cost[i-2])。'
});

add({
  title: '不同路径含障碍',
  desc: '机器人从左上角走到右下角，每次只能向右或向下。网格中 1 表示障碍，0 表示可走，求不同路径数量。',
  input: '第一行输入 n 和 m。\n\n接下来 n 行，每行 m 个 0 或 1。',
  output: '输出路径数量。',
  tags: ['动态规划', '矩阵'],
  difficulty: 'medium',
  range: '- 1 ≤ n,m ≤ 100\n- 答案在 long 范围内',
  cases: [{ input: '3 3\n0 0 0\n0 1 0\n0 0 0', output: '2' }, { input: '2 2\n0 1\n0 0', output: '1' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), m = sc.nextInt();
        long[][] dp = new long[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                int x = sc.nextInt();
                if (x == 1) dp[i][j] = 0;
                else if (i == 0 && j == 0) dp[i][j] = 1;
                else dp[i][j] = (i > 0 ? dp[i - 1][j] : 0) + (j > 0 ? dp[i][j - 1] : 0);
            }
        }
        System.out.println(dp[n - 1][m - 1]);
    }
}`,
  analysis: '若当前位置是障碍，路径数为 0；否则只能从上方或左方到达，所以路径数为两者之和。'
});

add({
  title: '完全平方数',
  desc: '给定正整数 n，求和为 n 的完全平方数的最少数量。',
  input: '输入一个正整数 n。',
  output: '输出最少数量。',
  tags: ['动态规划', '数学'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^4',
  cases: [{ input: '12', output: '3' }, { input: '13', output: '2' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[] dp = new int[n + 1];
        Arrays.fill(dp, 1_000_000); dp[0] = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j * j <= i; j++) dp[i] = Math.min(dp[i], dp[i - j * j] + 1);
        }
        System.out.println(dp[n]);
    }
}`,
  analysis: '设 dp[i] 为组成 i 的最少完全平方数数量。枚举最后使用的平方数 j*j，转移为 dp[i-j*j]+1。'
});

add({
  title: '解码方法',
  desc: '一串数字可以按 A=1, B=2, ..., Z=26 解码。给定只包含数字的字符串，求不同解码方法数。',
  input: '输入一行数字字符串 s。',
  output: '输出解码方法数。',
  tags: ['动态规划', '字符串'],
  difficulty: 'medium',
  range: '- 1 ≤ |s| ≤ 100\n- 答案在 int 范围内',
  cases: [{ input: '12', output: '2' }, { input: '226', output: '3' }, { input: '06', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine().trim();
        int n = s.length();
        int[] dp = new int[n + 1];
        dp[0] = 1;
        for (int i = 1; i <= n; i++) {
            if (s.charAt(i - 1) != '0') dp[i] += dp[i - 1];
            if (i >= 2) {
                int v = (s.charAt(i - 2) - '0') * 10 + (s.charAt(i - 1) - '0');
                if (v >= 10 && v <= 26) dp[i] += dp[i - 2];
            }
        }
        System.out.println(dp[n]);
    }
}`,
  analysis: 'dp[i] 表示前 i 个字符的解码数。最后可以单独解码一位，也可以在 10 到 26 之间时解码最后两位。'
});

add({
  title: '股票买卖含手续费',
  desc: '给定每天股票价格和每次卖出需要支付的手续费 fee，可以多次交易但不能同时持有多支股票，求最大利润。',
  input: '第一行输入 n 和 fee。\n\n第二行输入 n 个整数 prices。',
  output: '输出最大利润。',
  tags: ['动态规划', '贪心'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^5\n- 0 ≤ prices[i],fee ≤ 10^4',
  cases: [{ input: '6 2\n1 3 2 8 4 9', output: '8' }, { input: '3 1\n1 2 3', output: '1' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), fee = sc.nextInt();
        int hold = Integer.MIN_VALUE / 2, cash = 0;
        for (int i = 0; i < n; i++) {
            int price = sc.nextInt();
            int oldCash = cash;
            cash = Math.max(cash, hold + price - fee);
            hold = Math.max(hold, oldCash - price);
        }
        System.out.println(cash);
    }
}`,
  analysis: '维护两种状态：cash 表示不持股最大收益，hold 表示持股最大收益。卖出时扣手续费，逐日更新即可。'
});

add({
  title: '打家劫舍 II',
  desc: '一排房屋围成环，每间房有一定金额，相邻房屋不能同时偷。求能偷到的最大金额。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个非负整数。',
  output: '输出最大金额。',
  tags: ['动态规划'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^5\n- 0 ≤ nums[i] ≤ 10^4',
  cases: [{ input: '3\n2 3 2', output: '3' }, { input: '4\n1 2 3 1', output: '4' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = sc.nextInt();
        if (n == 1) { System.out.println(a[0]); return; }
        System.out.println(Math.max(rob(a, 0, n - 2), rob(a, 1, n - 1)));
    }
    static int rob(int[] a, int l, int r) {
        int prev = 0, cur = 0;
        for (int i = l; i <= r; i++) {
            int next = Math.max(cur, prev + a[i]);
            prev = cur;
            cur = next;
        }
        return cur;
    }
}`,
  analysis: '环形限制意味着第一间和最后一间不能同时选。拆成两个线性问题：不选最后一间，或不选第一间，取最大值。'
});

add({
  title: '电话号码字母组合数量',
  desc: '给定一个只包含数字 2-9 的字符串，返回它能表示的字母组合数量。数字到字母的映射与电话键盘相同。',
  input: '输入一行数字字符串 digits。',
  output: '输出可能的字母组合数量。',
  tags: ['回溯', '字符串'],
  range: '- 1 ≤ |digits| ≤ 20\n- digits 只包含 2 到 9',
  cases: [{ input: '23', output: '9' }, { input: '7', output: '4' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine().trim();
        int[] cnt = {0,0,3,3,3,3,3,4,3,4};
        long ans = 1;
        for (char c : s.toCharArray()) ans *= cnt[c - '0'];
        System.out.println(ans);
    }
}`,
  analysis: '每个数字可以独立选择若干个字母，总组合数就是各数字对应字母数量的乘积。'
});

add({
  title: '复原 IP 地址数量',
  desc: '给定一个只包含数字的字符串，判断可以复原出多少个合法 IP 地址。合法 IP 地址由四段组成，每段范围 0 到 255，且不能有多余前导零。',
  input: '输入一行数字字符串 s。',
  output: '输出合法 IP 地址数量。',
  tags: ['回溯', '字符串'],
  difficulty: 'medium',
  range: '- 1 ≤ |s| ≤ 20',
  cases: [{ input: '25525511135', output: '2' }, { input: '0000', output: '1' }],
  code: `
import java.util.*;

public class Main {
    static String s;
    static int ans = 0;
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        s = sc.nextLine().trim();
        dfs(0, 0);
        System.out.println(ans);
    }
    static void dfs(int idx, int part) {
        if (part == 4) { if (idx == s.length()) ans++; return; }
        if (idx == s.length()) return;
        int val = 0;
        for (int i = idx; i < Math.min(s.length(), idx + 3); i++) {
            val = val * 10 + (s.charAt(i) - '0');
            if (val > 255) break;
            if (i > idx && s.charAt(idx) == '0') break;
            dfs(i + 1, part + 1);
        }
    }
}`,
  analysis: '回溯枚举每一段的长度 1 到 3，并检查数值不超过 255、没有多余前导零。恰好分成四段且用完字符串才合法。'
});

add({
  title: '闰年判断',
  desc: '给定年份 y，判断它是否为闰年。闰年满足能被 400 整除，或能被 4 整除但不能被 100 整除。',
  input: '输入一个整数 y。',
  output: '若是闰年输出 YES，否则输出 NO。',
  tags: ['数学', '模拟'],
  range: '- 1 ≤ y ≤ 9999',
  cases: [{ input: '2024', output: 'YES' }, { input: '1900', output: 'NO' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int y = sc.nextInt();
        boolean ok = y % 400 == 0 || (y % 4 == 0 && y % 100 != 0);
        System.out.println(ok ? "YES" : "NO");
    }
}`,
  analysis: '按照闰年定义直接判断即可，注意整百年份必须能被 400 整除才是闰年。'
});

add({
  title: '十进制转二进制',
  desc: '给定一个非负整数 n，输出它的二进制表示。',
  input: '输入一个非负整数 n。',
  output: '输出 n 的二进制表示。',
  tags: ['数学', '字符串'],
  range: '- 0 ≤ n ≤ 10^9',
  cases: [{ input: '10', output: '1010' }, { input: '0', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        System.out.println(Integer.toBinaryString(n));
    }
}`,
  analysis: 'Java 的 Integer.toBinaryString 可以直接得到非负整数的二进制表示。'
});

add({
  title: '二进制转十进制',
  desc: '给定一个二进制字符串，输出它对应的十进制整数。',
  input: '输入一个只包含 0 和 1 的字符串。',
  output: '输出对应的十进制整数。',
  tags: ['数学', '字符串'],
  range: '- 1 ≤ 字符串长度 ≤ 31',
  cases: [{ input: '1010', output: '10' }, { input: '0', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String s = sc.nextLine().trim();
        long ans = 0;
        for (char c : s.toCharArray()) ans = ans * 2 + (c - '0');
        System.out.println(ans);
    }
}`,
  analysis: '从左到右读取二进制位，每加入一位都相当于当前值乘 2 再加该位。'
});

add({
  title: '完全数判断',
  desc: '如果一个正整数等于它所有真因子之和，则称为完全数。给定 n，判断它是否为完全数。',
  input: '输入一个正整数 n。',
  output: '若 n 是完全数输出 YES，否则输出 NO。',
  tags: ['数学', '枚举'],
  range: '- 1 ≤ n ≤ 10^9',
  cases: [{ input: '28', output: 'YES' }, { input: '12', output: 'NO' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long n = sc.nextLong();
        if (n == 1) { System.out.println("NO"); return; }
        long sum = 1;
        for (long i = 2; i * i <= n; i++) {
            if (n % i == 0) {
                sum += i;
                if (i != n / i) sum += n / i;
            }
        }
        System.out.println(sum == n ? "YES" : "NO");
    }
}`,
  analysis: '枚举到 sqrt(n) 查找因子，每找到 i 就同时加入 i 和 n/i。注意 1 不是完全数。'
});

add({
  title: '水仙花数判断',
  desc: '给定一个三位正整数，判断它是否为水仙花数。水仙花数满足各位数字立方和等于原数。',
  input: '输入一个三位正整数 n。',
  output: '若是水仙花数输出 YES，否则输出 NO。',
  tags: ['数学', '模拟'],
  range: '- 100 ≤ n ≤ 999',
  cases: [{ input: '153', output: 'YES' }, { input: '123', output: 'NO' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), x = n, sum = 0;
        while (x > 0) {
            int d = x % 10;
            sum += d * d * d;
            x /= 10;
        }
        System.out.println(sum == n ? "YES" : "NO");
    }
}`,
  analysis: '依次取出个位数字并累加三次方，最后与原数比较即可。'
});

add({
  title: '阶乘末尾零',
  desc: '给定整数 n，求 n! 的十进制表示末尾有多少个 0。',
  input: '输入整数 n。',
  output: '输出 n! 末尾 0 的个数。',
  tags: ['数学', '数论'],
  range: '- 0 ≤ n ≤ 10^9',
  cases: [{ input: '5', output: '1' }, { input: '25', output: '6' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        long n = sc.nextLong();
        long ans = 0;
        while (n > 0) {
            n /= 5;
            ans += n;
        }
        System.out.println(ans);
    }
}`,
  analysis: '末尾 0 来自因子 10，也就是 2 和 5 的配对。阶乘中 2 足够多，因此只需统计因子 5 的数量。'
});

add({
  title: '股票价格跨度',
  desc: '给定每天股票价格，输出每一天的价格跨度：连续多少天（包含当天）价格小于等于当天价格。',
  input: '第一行输入整数 n。\n\n第二行输入 n 个整数 price。',
  output: '输出 n 个跨度值。',
  tags: ['单调栈', '数组'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^5\n- 1 ≤ price[i] ≤ 10^9',
  cases: [{ input: '7\n100 80 60 70 60 75 85', output: '1 1 1 2 1 4 6' }, { input: '3\n10 20 30', output: '1 2 3' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt();
        Deque<int[]> st = new ArrayDeque<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int price = sc.nextInt();
            int span = 1;
            while (!st.isEmpty() && st.peek()[0] <= price) span += st.pop()[1];
            st.push(new int[]{price, span});
            if (i > 0) sb.append(' ');
            sb.append(span);
        }
        System.out.println(sb);
    }
}`,
  analysis: '栈中保存价格和它已经合并的跨度。当前价格可以合并所有不高于它的连续历史价格，得到跨度。'
});

add({
  title: '有向图是否有环',
  desc: '给定一个有向图，判断图中是否存在环。',
  input: '第一行输入 n 和 m，表示点数和边数。\n\n接下来 m 行，每行输入 u v，表示 u 指向 v。点编号为 1 到 n。',
  output: '若存在环输出 YES，否则输出 NO。',
  tags: ['图', '拓扑排序'],
  difficulty: 'medium',
  range: '- 1 ≤ n ≤ 10^5\n- 0 ≤ m ≤ 2×10^5',
  cases: [{ input: '3 3\n1 2\n2 3\n3 1', output: 'YES' }, { input: '3 2\n1 2\n2 3', output: 'NO' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        int n = sc.nextInt(), m = sc.nextInt();
        List<Integer>[] g = new ArrayList[n + 1];
        for (int i = 1; i <= n; i++) g[i] = new ArrayList<>();
        int[] indeg = new int[n + 1];
        for (int i = 0; i < m; i++) {
            int u = sc.nextInt(), v = sc.nextInt();
            g[u].add(v);
            indeg[v]++;
        }
        Queue<Integer> q = new LinkedList<>();
        for (int i = 1; i <= n; i++) if (indeg[i] == 0) q.offer(i);
        int cnt = 0;
        while (!q.isEmpty()) {
            int u = q.poll();
            cnt++;
            for (int v : g[u]) if (--indeg[v] == 0) q.offer(v);
        }
        System.out.println(cnt == n ? "NO" : "YES");
    }
}`,
  analysis: '拓扑排序能删除所有点说明无环；若最后仍有点无法入队，说明这些点处在环中或受环影响。'
});

add({
  title: '大整数乘一位数',
  desc: '给定一个非负大整数 a 和一位数字 d，求 a*d。',
  input: '第一行输入非负整数 a。\n\n第二行输入一位数字 d。',
  output: '输出乘积。',
  tags: ['字符串', '高精度'],
  difficulty: 'medium',
  range: '- 1 ≤ a 的长度 ≤ 10^5\n- 0 ≤ d ≤ 9',
  cases: [{ input: '12345\n9', output: '111105' }, { input: '999\n0', output: '0' }],
  code: `
import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        String a = sc.nextLine().trim();
        int d = sc.nextInt();
        if (d == 0 || a.equals("0")) { System.out.println(0); return; }
        int carry = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = a.length() - 1; i >= 0; i--) {
            int prod = (a.charAt(i) - '0') * d + carry;
            sb.append(prod % 10);
            carry = prod / 10;
        }
        while (carry > 0) { sb.append(carry % 10); carry /= 10; }
        System.out.println(sb.reverse());
    }
}`,
  analysis: '从低位开始模拟乘法，每一位乘以 d 后加上进位，当前位取模 10，新的进位除以 10。'
});

if (problems.length !== 62) {
  throw new Error(`Expected 62 problems, got ${problems.length}`);
}

const lines = [
  '-- 新增题目数据：question id 89-150',
  '-- 当前 question_all.sql AUTO_INCREMENT = 89，本文件执行后题目总数约为 150。',
  'SET NAMES utf8mb4;',
  'SET FOREIGN_KEY_CHECKS = 0;',
  '',
];

for (let i = 0; i < problems.length; i++) {
  const p = problems[i];
  const id = 89 + i;
  const submit = 520 + (i * 37) % 1600;
  const rate = p.difficulty === 'hard' ? 0.45 : p.difficulty === 'medium' ? 0.66 : 0.82;
  const accepted = Math.floor(submit * rate);
  const thumb = 10 + (i * 7) % 120;
  const favour = 8 + (i * 5) % 80;
  const vals = [
    id,
    sql(p.title),
    sql(content(p)),
    sql(JSON.stringify(p.tags)),
    sql(answer(p)),
    sql(p.difficulty),
    submit,
    accepted,
    sql(JSON.stringify(p.cases)),
    sql(JSON.stringify({ ...judgeConfig, timeLimit: p.timeLimit || 1000 })),
    thumb,
    favour,
    userId,
    sql('2026-04-27 12:00:00'),
    sql('2026-04-27 12:00:00'),
    0,
  ];
  lines.push(`INSERT INTO \`question\` (\`id\`, \`title\`, \`content\`, \`tags\`, \`answer\`, \`difficulty\`, \`submitNum\`, \`acceptedNum\`, \`judgeCase\`, \`judgeConfig\`, \`thumbNum\`, \`favourNum\`, \`userId\`, \`createTime\`, \`updateTime\`, \`isDelete\`) VALUES (${vals.join(', ')});`);
}

lines.push('');
lines.push('SET FOREIGN_KEY_CHECKS = 1;');

fs.writeFileSync(outPath, lines.join('\n'), 'utf8');
console.log(`Wrote ${outPath}`);
