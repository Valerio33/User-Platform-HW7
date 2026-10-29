package com.aston.userservice.service;

import com.aston.userservice.dto.*;
import com.aston.userservice.entity.User;
import com.aston.userservice.exception.UserNotFoundException;
import com.aston.userservice.mapper.UserMapper;
import com.aston.userservice.repository.UserRepository;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
    }

    public List<UserResponse> getAll() {
        return repository.findAll(Sort.by("id")).stream().map(UserMapper::toResponse).toList();
    }

    public UserResponse getById(Long id) {
        return UserMapper.toResponse(find(id));
    }

    @Transactional
    public UserResponse create(UserRequest request) {
        User user = new User(request.name(), request.email(), request.age());
        return UserMapper.toResponse(repository.saveAndFlush(user));
    }

    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User values = new User(request.name(), request.email(), request.age());
        User user = find(id);
        user.setName(values.getName());
        user.setEmail(values.getEmail());
        user.setAge(values.getAge());
        repository.flush();
        return UserMapper.toResponse(user);
    }

    @Transactional
    public void delete(Long id) {
        repository.delete(find(id));
        repository.flush();
    }

    private User find(Long id) {
        if (id == null || id <= 0)
            throw new IllegalArgumentException("ID должен быть положительным");
        return repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
    }
}
