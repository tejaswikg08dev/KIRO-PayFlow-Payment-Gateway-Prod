package com.payflow.identity.mapper;

import com.payflow.identity.dto.UserProfileResponse;
import com.payflow.identity.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "role", expression = "java(user.getRole().name())")
    UserProfileResponse toProfileResponse(User user);
}
