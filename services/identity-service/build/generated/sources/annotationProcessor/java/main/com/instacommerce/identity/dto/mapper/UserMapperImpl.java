package com.instacommerce.identity.dto.mapper;

import com.instacommerce.identity.domain.model.User;
import com.instacommerce.identity.dto.response.UserResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-02-07T21:12:21+0530",
    comments = "version: 1.5.5.Final, compiler: IncrementalProcessingEnvironment from gradle-language-java-9.0.0.jar, environment: Java 24.0.2 (Eclipse Adoptium)"
)
@Component
public class UserMapperImpl implements UserMapper {

    @Override
    public UserResponse toResponse(User user) {
        if ( user == null ) {
            return null;
        }

        UUID id = null;
        String email = null;
        String firstName = null;
        String lastName = null;
        String phone = null;
        List<String> roles = null;
        Instant createdAt = null;

        id = user.getId();
        email = user.getEmail();
        firstName = user.getFirstName();
        lastName = user.getLastName();
        phone = user.getPhone();
        List<String> list = user.getRoles();
        if ( list != null ) {
            roles = new ArrayList<String>( list );
        }
        createdAt = user.getCreatedAt();

        String status = user.getStatus().name();

        UserResponse userResponse = new UserResponse( id, email, firstName, lastName, phone, roles, status, createdAt );

        return userResponse;
    }

    @Override
    public List<UserResponse> toResponses(List<User> users) {
        if ( users == null ) {
            return null;
        }

        List<UserResponse> list = new ArrayList<UserResponse>( users.size() );
        for ( User user : users ) {
            list.add( toResponse( user ) );
        }

        return list;
    }
}
