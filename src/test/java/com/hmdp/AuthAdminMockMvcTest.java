package com.hmdp;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.common.ErrorCode;
import com.hmdp.entity.LoginLog;
import com.hmdp.entity.OperationLog;
import com.hmdp.entity.Permission;
import com.hmdp.entity.Role;
import com.hmdp.entity.RolePermission;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.entity.UserRole;
import com.hmdp.mapper.LoginLogMapper;
import com.hmdp.mapper.OperationLogMapper;
import com.hmdp.mapper.PermissionMapper;
import com.hmdp.mapper.RoleMapper;
import com.hmdp.mapper.RolePermissionMapper;
import com.hmdp.mapper.UserRoleMapper;
import com.hmdp.service.IMerchantShopService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.LOGIN_BLOCK_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_FAIL_COUNT_KEY;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class AuthAdminMockMvcTest {

    private static final String DEVICE = "mockmvc-device-auth-admin";
    private static final String BUYER_PHONE = "19900000001";
    private static final String MERCHANT_PHONE = "19900000002";
    private static final String ADMIN_PHONE = "19900000003";
    private static final String SHOP_NAME = "mockmvc-admin-shop";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Resource
    private IShopService shopService;

    @Resource
    private IMerchantShopService merchantShopService;

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private PermissionMapper permissionMapper;

    @Resource
    private RolePermissionMapper rolePermissionMapper;

    @Resource
    private UserRoleMapper userRoleMapper;

    @Resource
    private LoginLogMapper loginLogMapper;

    @Resource
    private OperationLogMapper operationLogMapper;

    private User buyer;
    private User merchant;
    private User admin;
    private Shop shop;

    @BeforeEach
    void setUp() {
        cleanup();
        ensureRoleAndPermissionData();
        buyer = createUser(BUYER_PHONE, "mock-buyer");
        merchant = createUser(MERCHANT_PHONE, "mock-merchant");
        admin = createUser(ADMIN_PHONE, "mock-admin");
        bindRole(buyer.getId(), "buyer");
        bindRole(merchant.getId(), "merchant");
        bindRole(admin.getId(), "admin");
        shop = new Shop()
                .setName(SHOP_NAME)
                .setTypeId(1L)
                .setImages("")
                .setArea("test")
                .setAddress("test")
                .setX(120.0)
                .setY(30.0)
                .setAvgPrice(100L)
                .setSold(0)
                .setComments(0)
                .setScore(45)
                .setOpenHours("10:00-22:00");
        shopService.save(shop);
    }

    @AfterEach
    void tearDown() {
        cleanup();
        StpUtil.logout();
    }

    @Test
    void loginFailuresReturnUnifiedErrorCodeAndBlockDevice() throws Exception {
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + BUYER_PHONE, "123456");

        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Device-Fingerprint", DEVICE)
                            .content("{\"phone\":\"" + BUYER_PHONE + "\",\"code\":\"000000\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value(ErrorCode.CAPTCHA_ERROR.getCode()));
        }

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device-Fingerprint", DEVICE)
                        .content("{\"phone\":\"" + BUYER_PHONE + "\",\"code\":\"000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_BLOCKED.getCode()));

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device-Fingerprint", DEVICE)
                        .content("{\"phone\":\"" + BUYER_PHONE + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.LOGIN_BLOCKED.getCode()));

        LoginLog latest = loginLogMapper.selectOne(new QueryWrapper<LoginLog>()
                .eq("phone", BUYER_PHONE)
                .orderByDesc("id")
                .last("LIMIT 1"));
        assertEquals(DEVICE, latest.getDeviceFingerprint());
        assertTrue(latest.getRiskLevel() >= 2);
        assertTrue(latest.getFailCount() >= 5);
    }

    @Test
    void adminCanDisableUserAndDisabledUserTokenIsRejected() throws Exception {
        String adminToken = tokenFor(admin.getId());
        String buyerToken = tokenFor(buyer.getId());

        mockMvc.perform(patch("/admin/rbac/users/status")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + buyer.getId() + ",\"status\":0,\"reason\":\"risk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/user/me")
                        .header("authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        User disabled = userService.getById(buyer.getId());
        assertEquals(0, disabled.getStatus());
    }

    @Test
    void adminCanDisableAndEnableUserRoleBinding() throws Exception {
        String adminToken = tokenFor(admin.getId());

        mockMvc.perform(patch("/admin/rbac/users/roles/status")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + merchant.getId() + ",\"roleKey\":\"merchant\",\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/admin/rbac/users/" + merchant.getId() + "/roles")
                        .header("authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(patch("/admin/rbac/users/roles/status")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + merchant.getId() + ",\"roleKey\":\"merchant\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/admin/rbac/users/" + merchant.getId() + "/roles")
                        .header("authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasItem("merchant")));
    }

    @Test
    void adminCanPageOperationLogsAndBindMerchantShop() throws Exception {
        String adminToken = tokenFor(admin.getId());

        mockMvc.perform(post("/admin/rbac/merchant-shops")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantUserId\":" + merchant.getId() + ",\"shopId\":" + shop.getId() + ",\"remark\":\"bind-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertTrue(merchantShopService.isShopOwner(merchant.getId(), shop.getId()));

        mockMvc.perform(get("/admin/rbac/merchant-shops")
                        .header("authorization", "Bearer " + adminToken)
                        .param("merchantUserId", String.valueOf(merchant.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value(1));

        mockMvc.perform(delete("/admin/rbac/merchant-shops")
                        .header("authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantUserId\":" + merchant.getId() + ",\"shopId\":" + shop.getId() + ",\"remark\":\"unbind-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/admin/rbac/operation-logs")
                        .header("authorization", "Bearer " + adminToken)
                        .param("module", "merchant_shop")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.total").value(2));
    }

    private String tokenFor(Long userId) {
        return StpUtil.createLoginSession(userId);
    }

    private User createUser(String phone, String nickName) {
        User user = new User()
                .setPhone(phone)
                .setPassword("")
                .setNickName(nickName)
                .setIcon("")
                .setStatus(1);
        userService.save(user);
        return user;
    }

    private void bindRole(Long userId, String roleKey) {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("role_key", roleKey));
        UserRole userRole = new UserRole()
                .setUserId(userId)
                .setRoleId(role.getId())
                .setStatus(1);
        userRoleMapper.insert(userRole);
    }

    private void ensureRoleAndPermissionData() {
        ensureRole("buyer", "Buyer");
        ensureRole("merchant", "Merchant");
        ensureRole("admin", "Admin");
        ensurePermission("user:read", "User read");
        ensurePermission("user:disable", "User disable");
        ensurePermission("role:assign", "Role assign");
        ensurePermission("system:log:read", "System log read");
        ensurePermission("shop:update", "Shop update");
        ensurePermission("shop:update:own", "Shop update own");
        ensurePermission("voucher:create:own", "Voucher create own");
        ensurePermission("voucher:seckill", "Voucher seckill");
        bindRolePermissions("admin", "user:read", "user:disable", "role:assign", "system:log:read", "shop:update");
        bindRolePermissions("merchant", "shop:update:own", "voucher:create:own");
        bindRolePermissions("buyer", "voucher:seckill");
    }

    private void ensureRole(String roleKey, String roleName) {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("role_key", roleKey));
        if (role == null) {
            roleMapper.insert(new Role().setRoleKey(roleKey).setRoleName(roleName).setStatus(1));
        }
    }

    private void ensurePermission(String code, String name) {
        Permission permission = permissionMapper.selectOne(new QueryWrapper<Permission>().eq("permission_code", code));
        if (permission == null) {
            permissionMapper.insert(new Permission().setPermissionCode(code).setPermissionName(name).setStatus(1));
        }
    }

    private void bindRolePermissions(String roleKey, String... permissionCodes) {
        Role role = roleMapper.selectOne(new QueryWrapper<Role>().eq("role_key", roleKey));
        for (String permissionCode : permissionCodes) {
            Permission permission = permissionMapper.selectOne(new QueryWrapper<Permission>()
                    .eq("permission_code", permissionCode));
            Integer count = rolePermissionMapper.selectCount(new QueryWrapper<RolePermission>()
                    .eq("role_id", role.getId())
                    .eq("permission_id", permission.getId()));
            if (count == null || count == 0) {
                rolePermissionMapper.insert(new RolePermission()
                        .setRoleId(role.getId())
                        .setPermissionId(permission.getId())
                        .setStatus(1));
            }
        }
    }

    private void cleanup() {
        Set<String> phones = new HashSet<>(Arrays.asList(BUYER_PHONE, MERCHANT_PHONE, ADMIN_PHONE));
        for (String phone : phones) {
            User user = userService.query().eq("phone", phone).one();
            if (user != null) {
                userRoleMapper.delete(new QueryWrapper<UserRole>().eq("user_id", user.getId()));
                merchantShopService.remove(new QueryWrapper<com.hmdp.entity.MerchantShop>()
                        .eq("merchant_user_id", user.getId()));
                userService.removeById(user.getId());
            }
            stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
            stringRedisTemplate.delete(LOGIN_FAIL_COUNT_KEY + phone + ":" + DEVICE);
            stringRedisTemplate.delete(LOGIN_BLOCK_KEY + phone + ":" + DEVICE);
        }
        shopService.remove(new QueryWrapper<Shop>().eq("name", SHOP_NAME));
        loginLogMapper.delete(new QueryWrapper<LoginLog>().in("phone", phones));
        operationLogMapper.delete(new QueryWrapper<OperationLog>()
                .and(wrapper -> wrapper.like("detail", "bind-test").or().like("detail", "unbind-test")));
    }
}
