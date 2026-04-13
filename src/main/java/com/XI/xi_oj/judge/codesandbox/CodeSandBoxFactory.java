package com.XI.xi_oj.judge.codesandbox;

import com.XI.xi_oj.judge.codesandbox.impl.ExampleCodeSandBox;
import com.XI.xi_oj.judge.codesandbox.impl.RemoteCodeSandBox;

/***
 * 根据用户传入的字符串生成对应的沙箱代码实例
 * 单例工厂
 */
public class CodeSandBoxFactory {
    /**
     * 根据传入的类型创建并返回相应的沙箱实例
     * @param type 沙箱类型字符串，可以是"example"、"remote"、"thirdParty"等
     * @return 返回对应类型的CodeSandBox实例，如果类型不匹配则返回默认的ExampleCodeSandBox实例
     */
    public static CodeSandBox newInstance(String type) {
        // 使用switch语句根据传入的类型创建不同的沙箱实例
        switch (type){
            // 当类型为"example"时，创建并返回ExampleCodeSandBox实例
            case "example":
                return new ExampleCodeSandBox();
            // 当类型为"remote"时，创建并返回ExampleCodeSandBox实例
            case "remote":
                return new RemoteCodeSandBox();
            // 当类型为"thirdParty"时，创建并返回ExampleCodeSandBox实例
            case "thirdParty":
                return new ExampleCodeSandBox();
            // 当类型不匹配以上任何情况时，返回默认的ExampleCodeSandBox实例
            default:
                return new ExampleCodeSandBox();
        }
    }
}
