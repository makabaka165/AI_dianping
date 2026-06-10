package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Mapper
public interface BlogMapper extends BaseMapper<Blog> {
    /**
     * 根据店铺ID获取所有博主评价
     */
    @Select("SELECT * FROM tb_blog " +
            "WHERE shop_id = #{shopId} " +
            "AND status = 1 " +
            "AND deleted = 0 " +
            "ORDER BY create_time DESC")
    List<Blog> selectBlogsByShopId(@Param("shopId") Long shopId);

    /**
     * 获取店铺的博客统计信息
     */
    @Select("SELECT COUNT(*) as total_count, AVG(liked) as avg_liked, SUM(liked) as total_liked " +
            "FROM tb_blog " +
            "WHERE shop_id = #{shopId} " +
            "AND status = 1 " +
            "AND deleted = 0")
    Map<String, Object> selectBlogStatsByShopId(@Param("shopId") Long shopId);

    /**
     * 获取高质量博客(点赞数高、内容丰富)
     */
    @Select("SELECT * FROM tb_blog WHERE shop_id = #{shopId} " +
            "AND status = 1 " +
            "AND deleted = 0 " +
            "AND LENGTH(content) > 50 " +
            "AND liked > #{minLiked} " +
            "ORDER BY liked DESC, create_time DESC " +
            "LIMIT #{limit}")
    List<Blog> selectQualityBlogsByShopId(@Param("shopId") Long shopId,
                                          @Param("minLiked") Integer minLiked,
                                          @Param("limit") Integer limit);
}
