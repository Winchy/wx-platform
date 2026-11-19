package com.jistack.platform.module.authorizer;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthorizerTokenMapper extends BaseMapper<AuthorizerToken> {
}
