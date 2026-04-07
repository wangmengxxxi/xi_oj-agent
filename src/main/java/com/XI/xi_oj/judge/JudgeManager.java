package com.XI.xi_oj.judge;


import com.XI.xi_oj.judge.strategy.DefaultJudgeStrategy;
import com.XI.xi_oj.judge.strategy.JavaLanguageJudgeStrategy;
import com.XI.xi_oj.judge.strategy.JudgeContext;
import com.XI.xi_oj.judge.strategy.JudgeStrategy;
import com.XI.xi_oj.judge.codesandbox.model.JudgeInfo;
import com.XI.xi_oj.model.entity.QuestionSubmit;
import org.springframework.stereotype.Service;

/**
 * 判题管理（简化调用）
 */
@Service
public class JudgeManager {

    /**
     * 执行判题
     *
     * @param judgeContext
     * @return
     */
    public JudgeInfo doJudge(JudgeContext judgeContext) {
        QuestionSubmit questionSubmit = judgeContext.getQuestionSubmit();
        String language = questionSubmit.getLanguage();
        JudgeStrategy judgeStrategy = new DefaultJudgeStrategy();
        if ("java".equals(language)) {
            judgeStrategy = new JavaLanguageJudgeStrategy();
        }
        return judgeStrategy.doJudge(judgeContext);
    }

}
