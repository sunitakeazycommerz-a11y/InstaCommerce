package com.instacommerce.notification.service;

import java.util.Optional;
import java.util.UUID;

public interface UserDirectoryClient {
    Optional<UserContact> findUser(UUID userId);
}
