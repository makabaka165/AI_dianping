package com.hmdp.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PermissionQueryMapper {

    @Select("SELECT DISTINCT r.role_key " +
            "FROM sys_user_role ur " +
            "JOIN tb_user u ON u.id = ur.user_id AND u.status = 1 " +
            "JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} " +
            "AND ur.status = 1 " +
            "AND r.status = 1 " +
            "ORDER BY r.role_key")
    List<String> selectRoleKeysByUserId(@Param("userId") Long userId);

    @Select("SELECT DISTINCT p.permission_code " +
            "FROM sys_user_role ur " +
            "JOIN tb_user u ON u.id = ur.user_id AND u.status = 1 " +
            "JOIN sys_role r ON r.id = ur.role_id " +
            "JOIN sys_role_permission rp ON rp.role_id = r.id " +
            "JOIN sys_permission p ON p.id = rp.permission_id " +
            "WHERE ur.user_id = #{userId} " +
            "AND ur.status = 1 " +
            "AND r.status = 1 " +
            "AND rp.status = 1 " +
            "AND p.status = 1 " +
            "ORDER BY p.permission_code")
    List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);
}
