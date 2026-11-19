package com.jistack.platform.module.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final SysUserMapper userMapper;
    private final PlatformConfigMapper configMapper;
    private final AuthService authService;

    @Override
    public void run(ApplicationArguments args) {
        if (configMapper.selectById(PlatformConfig.SINGLETON_ID) == null) {
            PlatformConfig config = new PlatformConfig();
            config.setId(PlatformConfig.SINGLETON_ID);
            config.setStatus(0);
            configMapper.insert(config);
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "admin")) == 0) {
            SysUser admin = new SysUser();
            admin.setUsername("admin");
            admin.setPasswordHash(authService.encode("admin123"));
            admin.setNickname("平台管理员");
            admin.setRole("ADMIN");
            admin.setStatus(1);
            userMapper.insert(admin);
            log.warn("已创建默认账号 admin/admin123，请立即在正式环境修改密码");
        }
    }
}
