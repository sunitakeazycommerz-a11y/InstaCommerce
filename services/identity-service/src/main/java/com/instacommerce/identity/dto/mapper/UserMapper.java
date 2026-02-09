package com.instacommerce.identity.dto.mapper;

import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.dto.response.UserResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {
    @Mapping(target = "status", expression = "java(user.getStatus().name())")
    UserResponse toResponse(User user);

    List<UserResponse> toResponses(List<User> users);
}
