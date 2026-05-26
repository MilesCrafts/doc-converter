package com.office.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.office.ai.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}