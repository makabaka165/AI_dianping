package com.hmdp.ai.application.session;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.context.SaTokenContext;
import cn.dev33.satoken.context.SaTokenContextForThreadLocal;
import cn.dev33.satoken.context.SaTokenContextForThreadLocalStorage;
import cn.dev33.satoken.servlet.model.SaRequestForServlet;
import cn.dev33.satoken.servlet.model.SaResponseForServlet;
import cn.dev33.satoken.servlet.model.SaStorageForServlet;
import cn.dev33.satoken.stp.StpUtil;
import com.hmdp.ai.application.dto.session.SessionBootstrapResponse;
import com.hmdp.ai.application.security.AiAuthorizationService;
import com.hmdp.ai.domain.security.AiPermission;
import com.hmdp.ai.domain.security.AuthorizationContext;
import com.hmdp.ai.shared.exception.AiPlatformException;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.ResultSet;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionBootstrapApplicationServiceTest {
    private SaTokenContext previousContext;

    @BeforeEach
    void setUpSaToken() {
        previousContext = SaManager.getSaTokenContext();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        SaManager.setSaTokenContext(new SaTokenContextForThreadLocal());
        SaTokenContextForThreadLocalStorage.setBox(
                new SaRequestForServlet(request),
                new SaResponseForServlet(response),
                new SaStorageForServlet(request));
    }

    @AfterEach
    void tearDownSaToken() {
        try {
            if (StpUtil.isLogin()) {
                StpUtil.logout();
            }
        } catch (RuntimeException ignored) {
            // ignore cleanup failures in unit tests
        }
        SaTokenContextForThreadLocalStorage.clearBox();
        SaManager.setSaTokenContext(previousContext);
    }

    @Test
    void returnsMembershipsAndDefaultScopeForActiveMember() throws Exception {
        StpUtil.login(1L);
        IUserService users = mock(IUserService.class);
        User user = new User();
        user.setId(1L);
        user.setNickName("owner");
        user.setIcon("icon.png");
        when(users.getById(1L)).thenReturn(user);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("1"))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("tenant_id")).thenReturn("default");
            when(rs.getString("tenant_name")).thenReturn("Default tenant");
            when(rs.getString("workspace_id")).thenReturn("default");
            when(rs.getString("workspace_name")).thenReturn("Default workspace");
            when(rs.getString("role")).thenReturn("OWNER");
            when(rs.getString("status")).thenReturn("ACTIVE");
            return Collections.singletonList(mapper.mapRow(rs, 0));
        });

        AiAuthorizationService authorization = mock(AiAuthorizationService.class);
        when(authorization.authorize("1", "default", "default"))
                .thenReturn(new AuthorizationContext(EnumSet.of(AiPermission.ADMIN, AiPermission.AGENT_RUN)));

        SessionBootstrapApplicationService service =
                new SessionBootstrapApplicationService(jdbc, users, authorization);

        SessionBootstrapResponse response = service.bootstrap();

        assertThat(response.getUser().getId()).isEqualTo("1");
        assertThat(response.getMemberships()).hasSize(1);
        assertThat(response.getMemberships().get(0).isDefault()).isTrue();
        assertThat(response.getMemberships().get(0).getPermissions()).contains("AGENT_RUN", "ADMIN");
        assertThat(response.getDefaultScope().getTenantId()).isEqualTo("default");
        assertThat(response.getDefaultScope().getWorkspaceId()).isEqualTo("default");
    }

    @Test
    void returnsEmptyMembershipsWithoutDefaultScope() {
        StpUtil.login(2L);
        IUserService users = mock(IUserService.class);
        User user = new User();
        user.setId(2L);
        user.setNickName("guest");
        user.setIcon("");
        when(users.getById(2L)).thenReturn(user);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("2"))).thenReturn(Collections.emptyList());
        AiAuthorizationService authorization = mock(AiAuthorizationService.class);

        SessionBootstrapApplicationService service =
                new SessionBootstrapApplicationService(jdbc, users, authorization);

        SessionBootstrapResponse response = service.bootstrap();

        assertThat(response.getMemberships()).isEmpty();
        assertThat(response.getDefaultScope()).isNull();
    }

    @Test
    void rejectsInactiveMembershipFromDefaultScope() throws Exception {
        StpUtil.login(3L);
        IUserService users = mock(IUserService.class);
        User user = new User();
        user.setId(3L);
        user.setNickName("inactive");
        user.setIcon("");
        when(users.getById(3L)).thenReturn(user);

        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq("3"))).thenAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString("tenant_id")).thenReturn("default");
            when(rs.getString("tenant_name")).thenReturn("Default tenant");
            when(rs.getString("workspace_id")).thenReturn("default");
            when(rs.getString("workspace_name")).thenReturn("Default workspace");
            when(rs.getString("role")).thenReturn("RUNNER");
            when(rs.getString("status")).thenReturn("DISABLED");
            return Collections.singletonList(mapper.mapRow(rs, 0));
        });
        AiAuthorizationService authorization = mock(AiAuthorizationService.class);

        SessionBootstrapApplicationService service =
                new SessionBootstrapApplicationService(jdbc, users, authorization);

        SessionBootstrapResponse response = service.bootstrap();

        assertThat(response.getMemberships()).hasSize(1);
        assertThat(response.getMemberships().get(0).getPermissions()).isEmpty();
        assertThat(response.getMemberships().get(0).isDefault()).isFalse();
        assertThat(response.getDefaultScope()).isNull();
    }

    @Test
    void rejectsAnonymousBootstrap() {
        IUserService users = mock(IUserService.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiAuthorizationService authorization = mock(AiAuthorizationService.class);
        SessionBootstrapApplicationService service =
                new SessionBootstrapApplicationService(jdbc, users, authorization);

        assertThatThrownBy(service::bootstrap).isInstanceOf(AiPlatformException.class);
    }
}
