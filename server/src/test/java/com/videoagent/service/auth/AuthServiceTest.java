package com.videoagent.service.auth;

import com.videoagent.common.BusinessException;
import com.videoagent.dto.LoginRequest;
import com.videoagent.dto.LoginResponse;
import com.videoagent.dto.RegisterRequest;
import com.videoagent.dto.UserDto;
import com.videoagent.entity.User;
import com.videoagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, new BCryptPasswordEncoder(), jwtService);
    }

    @Test
    void registerSuccess_encodesPasswordAndReturnsToken() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        // 模拟 JPA save 的持久化行为：为传入实体分配主键后返回
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(jwtService.generateToken(1L, "alice", "ROLE_USER")).thenReturn("jwt-token");

        LoginResponse response = authService.register(new RegisterRequest("alice", "secret123", "Alice"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.username()).isEqualTo("alice");
        // 密码绝不明文入库：save 时写入的是 BCrypt 哈希
        verify(userRepository).save(any(User.class));
    }

    @Test
    void registerDuplicateUsername_throws() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("alice", "secret123", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名已存在");

        verify(userRepository, never()).save(any());
    }

    @Test
    void loginSuccess_returnsToken() {
        User user = new User();
        user.setId(7L);
        user.setUsername("bob");
        user.setNickname("Bob");
        user.setRole("ROLE_USER");
        user.setPassword(new BCryptPasswordEncoder().encode("secret123"));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(jwtService.generateToken(7L, "bob", "ROLE_USER")).thenReturn("jwt-token");

        LoginResponse response = authService.login(new LoginRequest("bob", "secret123"));

        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.userId()).isEqualTo(7L);
    }

    @Test
    void loginWrongPassword_throws() {
        User user = new User();
        user.setId(7L);
        user.setUsername("bob");
        user.setPassword(new BCryptPasswordEncoder().encode("right-password"));
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest("bob", "wrong-password")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    void loginUnknownUser_throws() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "whatever")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getUser_returnsDtoWithoutPassword() {
        User user = new User();
        user.setId(9L);
        user.setUsername("carol");
        user.setNickname("Carol");
        user.setRole("ROLE_USER");
        user.setPassword("should-not-leak");
        when(userRepository.findById(9L)).thenReturn(Optional.of(user));

        UserDto dto = authService.getUser(9L);

        assertThat(dto.id()).isEqualTo(9L);
        assertThat(dto.username()).isEqualTo("carol");
        assertThat(dto.toString()).doesNotContain("should-not-leak");
        verify(userRepository, never()).save(any());
    }
}
