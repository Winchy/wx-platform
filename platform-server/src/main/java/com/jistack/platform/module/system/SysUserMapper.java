package com.jistack.platform.module.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按手机号取账号（uk_sys_user_phone 保证最多一条）。
     * default 方法不会被 MyBatis 当成 SQL 语句，界面与测试都能直接桩这一个入口，
     * 不必去猜调用方自己拼出来的查询条件。
     */
    default SysUser selectByPhone(String phone) {
        return selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone));
    }
}
