package com.ffp.feature_flag_platform.auth;

import com.ffp.feature_flag_platform.audit.AuditEntry.Action;
import com.ffp.feature_flag_platform.audit.AuditEntry.ResourceType;
import com.ffp.feature_flag_platform.audit.AuditService;
import com.ffp.feature_flag_platform.auth.AuthDtos.CreateUserRequest;
import com.ffp.feature_flag_platform.auth.AuthDtos.UserResponse;
import com.ffp.feature_flag_platform.common.ConflictException;
import com.ffp.feature_flag_platform.common.NotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    public UserService(UserRepository users, PasswordEncoder passwordEncoder, AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll(Sort.by("email")).stream().map(UserResponse::from).toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("A user with email '%s' already exists".formatted(request.email()));
        }
        User user = users.save(new User(request.email().toLowerCase(), passwordEncoder.encode(request.password()),
                request.displayName(), request.role()));
        audit.record(null, null, ResourceType.USER, user.getEmail(), Action.CREATED,
                "Created user with role " + user.getRole(), null, Map.of("email", user.getEmail(), "role", user.getRole()));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateRole(Long id, Role role) {
        User user = users.findById(id).orElseThrow(() -> new NotFoundException("User", String.valueOf(id)));
        if (user.getEmail().equalsIgnoreCase(CurrentUser.actor()) && role != Role.ADMIN) {
            throw new ConflictException("You cannot remove your own admin role");
        }
        Role before = user.getRole();
        user.setRole(role);
        audit.record(null, null, ResourceType.USER, user.getEmail(), Action.UPDATED,
                "Changed role from %s to %s".formatted(before, role), Map.of("role", before), Map.of("role", role));
        return UserResponse.from(user);
    }
}
