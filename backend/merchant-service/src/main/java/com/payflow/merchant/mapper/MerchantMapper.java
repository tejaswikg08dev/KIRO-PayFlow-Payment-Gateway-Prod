package com.payflow.merchant.mapper;

import com.payflow.merchant.dto.MerchantRegisterRequest;
import com.payflow.merchant.dto.MerchantResponse;
import com.payflow.merchant.model.Merchant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper(componentModel = "spring")
public interface MerchantMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", constant = "true")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Merchant toEntity(MerchantRegisterRequest request);

    MerchantResponse toResponse(Merchant merchant);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "active", ignore = true)
    void updateEntity(MerchantRegisterRequest request, @MappingTarget Merchant merchant);
}
