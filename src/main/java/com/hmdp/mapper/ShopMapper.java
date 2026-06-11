package com.hmdp.mapper;

import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface ShopMapper extends BaseMapper<Shop> {

    @Select("<script>" +
            "SELECT s.* FROM tb_shop s " +
            "LEFT JOIN tb_shop_type t ON s.type_id = t.id " +
            "WHERE 1 = 1 " +
            "<if test='category != null and category != \"\"'> " +
            "AND (t.name LIKE CONCAT('%', #{category}, '%') OR s.name LIKE CONCAT('%', #{category}, '%')) " +
            "</if> " +
            "ORDER BY s.score DESC, s.comments DESC, s.sold DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<Shop> selectRecommendCandidates(@Param("category") String category,
                                          @Param("limit") Integer limit);

}
