package com.XI.xi_oj.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.XI.xi_oj.model.entity.QuestionSubmit;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
* @author 李鱼皮
*
*/
public interface QuestionSubmitMapper extends BaseMapper<QuestionSubmit> {

    List<Map<String, Object>> selectTagMastery(@Param("userId") Long userId);

    List<Map<String, Object>> selectFailedSubmitStats(@Param("userId") Long userId);

}




