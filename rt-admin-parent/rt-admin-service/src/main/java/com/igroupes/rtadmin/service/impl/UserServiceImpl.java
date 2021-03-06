package com.igroupes.rtadmin.service.impl;


import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Maps;
import com.igroupes.rtadmin.config.LoginProperties;
import com.igroupes.rtadmin.constant.RtAdminConstant;
import com.igroupes.rtadmin.dto.UserInfo;
import com.igroupes.rtadmin.dto.reponse.UserBaseInfoResponse;
import com.igroupes.rtadmin.dto.reponse.UserInfoPageResponse;
import com.igroupes.rtadmin.dto.reponse.UserResponse;
import com.igroupes.rtadmin.dto.reponse.UserTreeResponse;
import com.igroupes.rtadmin.dto.request.*;
import com.igroupes.rtadmin.entity.*;
import com.igroupes.rtadmin.enums.ErrorCode;
import com.igroupes.rtadmin.file.exception.RtAdminException;
import com.igroupes.rtadmin.result.SystemUserResult;
import com.igroupes.rtadmin.service.IUserService;
import com.igroupes.rtadmin.service.IVerifyCodeManager;
import com.igroupes.rtadmin.service.raw.*;
import com.igroupes.rtadmin.util.*;
import com.igroupes.rtadmin.vo.ResultVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.util.Lists;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.awt.image.BufferedImage;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.igroupes.rtadmin.constant.RtAdminConstant.ADMIN_SUPER_ID;

@Service
@Slf4j
public class UserServiceImpl implements IUserService {
    @Autowired
    private LoginProperties loginProperties;
    @Autowired
    private IVerifyCodeManager verifyCodeManage;
    @Autowired
    private SystemLoginSessionService systemLoginSessionService;
    @Autowired
    private SystemPermissionService systemPermissionService;
    @Autowired
    private SystemUserService systemUserService;
    @Autowired
    private SystemUserRoleService systemUserRoleService;
    @Autowired
    private SystemRoleService systemRoleService;

    @Override
    public ResultVO login(UserLoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
//        // 校验验证码
//        String cookieValue = CookieUtils.getCookieValue(httpRequest, RtAdminConstant.VERIFY_CODE_COOKIE_KEY);
//        if (StringUtils.isBlank(cookieValue)) {
//            log.error("cookie没有获取到，无法校验验证码");
//            return ResultVO.error(ErrorCode.VERIFY_CODE_ERROR);
//        }
//        // 删除cookie
//        CookieUtils.deleteCookie(httpRequest, httpResponse, RtAdminConstant.VERIFY_CODE_COOKIE_KEY);
//        Long codeKey = Long.valueOf(cookieValue);
//        if (!verifyCodeManage.isRightVerifyCode(codeKey, request.getCode())) {
//            log.error("except: {} ,but: {}", codeKey, request.getCode());
//            verifyCodeManage.deleteVerifyCode(codeKey);
//            return ResultVO.error(ErrorCode.VERIFY_CODE_ERROR);
//        }
//        verifyCodeManage.deleteVerifyCode(codeKey);
        SystemUserEntity userFind = new SystemUserEntity();
        userFind.setUsername(request.getUsername());
        Wrapper<SystemUserEntity> userWrapper = new QueryWrapper<>(userFind);
        SystemUserEntity userEntity = systemUserService.getOne(userWrapper);
        if (userEntity == null) {
            return ResultVO.error(ErrorCode.USER_NOT_EXISTS);
        }
        UserUtils.checkPassword(userEntity.getPassword(), userEntity.getSalt(), request.getPassword());
        // 生成token
        String token = createToken(userEntity);
        if (StringUtils.isBlank(token)) {
            log.error("token gen fail");
            return ResultVO.error(ErrorCode.SYSTEM_ERROR);
        }
        UserResponse userResponse = new UserResponse();
        userResponse.setKey(loginProperties.getTokenKey());
        userResponse.setToken(token);

        // 添加用户信息到request,方便拦截使用
        addUserInfo(httpRequest, userEntity);
        return ResultVO.success(userResponse);
    }


    private void addUserInfo(HttpServletRequest request, SystemUserEntity userEntity) {
        UserInfo userInfo = new UserInfo();
        BeanUtils.copyProperties(userEntity, userInfo);
        request.setAttribute(RtAdminConstant.USER_INFO_REQUEST_ATTRIBUTE_KEY, userInfo);
    }


    @Override
    public ResultVO logout(HttpServletRequest request, UserInfo userInfo) {
        if (userInfo == null || userInfo.getId() == null) {
            return ResultVO.error(ErrorCode.USER_NOT_EXISTS);
        }
        deleteToken(request, userInfo);
        return ResultVO.success();
    }

    private void deleteToken(HttpServletRequest request, UserInfo userInfo) {
//        String token = request.getHeader(loginProperties.getTokenKey());
        SystemLoginSessionEntity loginSession = new SystemLoginSessionEntity();
        // 应该删除这个用户的所有token,防止允许一个用户多处登录
//        loginSession.setToken(token);
        loginSession.setUserId(userInfo.getId());
        Wrapper<SystemLoginSessionEntity> wrapper = new QueryWrapper<>(loginSession);
        systemLoginSessionService.remove(wrapper);
    }

    @Override
    public ResultVO changePassword(HttpServletRequest request, ChangePasswordRequest changePasswordRequest, UserInfo userInfo) {
        if (userInfo == null || userInfo.getId() == null) {
            return ResultVO.error(ErrorCode.USER_NOT_EXISTS);
        }

        if (changePasswordRequest.getOldPassword().equals(changePasswordRequest.getPassword())) {
            log.warn("修改密码和原始密码都是：{}", changePasswordRequest.getOldPassword());
            return ResultVO.error(ErrorCode.PASSWORD_REPEAT);
        }
        SystemUserEntity userDO = new SystemUserEntity();
        BeanUtils.copyProperties(userInfo, userDO);
        UserUtils.checkPassword(userDO.getPassword(), userDO.getSalt(), changePasswordRequest.getOldPassword());
        // 如果密码校验成功
        String passwd = UserUtils.genPassword(changePasswordRequest.getPassword(), userInfo.getSalt());
        userInfo.setPassword(passwd);
        userDO.setPassword(passwd);
        systemUserService.updateById(userDO);
        deleteToken(request, userInfo);
        return ResultVO.success();
    }

    @Override
    public ResultVO addUser(UserInfo userInfo, UserAddRequest request) {
        checkNewUser(userInfo, request);
        SystemUserEntity userDO = new SystemUserEntity();
        userDO.setAvatar(request.getAvatar());
        userDO.setDesc(request.getDesc());
        userDO.setNickname(request.getNickname());
        userDO.setParentId(userInfo.getId());
        String salt = UserUtils.genSalt();
        userDO.setPassword(UserUtils.genPassword(request.getPassword(), salt));
        userDO.setSalt(salt);
        userDO.setUsername(request.getUsername());
        userDO.setParentChain(genChildLevelParentChain(userInfo.getId(), userInfo.getParentChain()));
        if (!systemUserService.save(userDO)) {
            log.error("插入用户信息:{}失败", userDO);
            throw new RtAdminException(ErrorCode.SYSTEM_ERROR);
        } else {
            return ResultVO.success();
        }
    }

    private void checkNewUser(UserInfo userInfo, UserAddRequest request) {
        UserUtils.checkPasswordLength(request.getPassword());
        // 校验用户名是不是存在
        SystemUserEntity userDOFind = new SystemUserEntity();
        userDOFind.setUsername(request.getUsername());
        SystemUserEntity userDO = systemUserService.getOne(new QueryWrapper<>(userDOFind));
        if (userDO != null) {
            throw new RtAdminException(ErrorCode.USER_EXISTS);
        }
    }

    @Override
    public ResultVO updateUser(UserInfo userInfo, Long userId, UserUpdateRequest request) {
        // 只有创建者才有权限
        if (userId == null) {
            throw new RtAdminException(ErrorCode.PARAM_ERROR);
        }
        SystemUserEntity userDO = systemUserService.getById(userId);
        if (userDO == null) {
            log.error("userId :{} is not exists", userId);
            throw new RtAdminException(ErrorCode.USER_NOT_EXISTS);
        }
        if (!isHigherLevel(userInfo.getId(), userId)) {
            log.error("userId:{}必须是userId:{}的直接/间隔创建者,才有权限删除用户", userInfo.getId(), userId);
            throw new RtAdminException(ErrorCode.PERMISSION_DENIED);
        }

        userDO.setUsername(request.getUsername());
        userDO.setNickname(request.getNickname());
        userDO.setDesc(request.getDesc());
        userDO.setAvatar(request.getAvatar());
        if (StringUtils.isNotBlank(request.getPassword())) {
            String newPassword = UserUtils.genPassword(request.getPassword(), userDO.getSalt());
            if (!newPassword.equals(userDO.getPassword())) {
                userDO.setPassword(newPassword);
            }
        }
        // 更新
        systemUserService.updateById(userDO);
        return ResultVO.success();
    }

    /**
     * 删除用户
     *
     * @param userInfo
     * @param userId
     * @return
     */
    @Override
    public ResultVO deleteUser(UserInfo userInfo, Long userId) {
        SystemUserEntity systemUserEntity = systemUserService.getById(userId);
        if (systemUserEntity == null) {
            log.error("userId :{} is not exists", userId);
            throw new RtAdminException(ErrorCode.USER_NOT_EXISTS);
        }
        // 只有创建者能够删除用户
        if (!isHigherLevel(userInfo.getId(), userId)) {
            log.error("userId:{}必须是userId:{}的直接/间隔创建者,才有权限删除用户", userInfo.getId(), userId);
            throw new RtAdminException(ErrorCode.PERMISSION_DENIED);
        }
        if (!systemUserService.removeById(userId)) {
            log.error("删除用户id:{}失败", userId);
            throw new RtAdminException(ErrorCode.SYSTEM_ERROR);
        } else {
            return ResultVO.success();
        }
    }

    @Override
    public ResultVO getUserList(UserInfo userInfo, UserInfoPageRequest request) {
        try {
            if (StringUtils.isNotBlank(request.getFilter())) {
                // 经过框架处理，会转为url编码
                request.setFilter(URLDecoder.decode(request.getFilter(), RtAdminConstant.URL_DECODE_CHARSET));
            }
            if (StringUtils.isNotBlank(request.getRoleIds())) {
                request.setRoleIds(URLDecoder.decode(request.getRoleIds(), RtAdminConstant.URL_DECODE_CHARSET));
            }
        } catch (Exception ex) {
            throw new RtAdminException(ErrorCode.PARAM_ERROR);
        }
        Page<SystemUserResult> page = new Page<>(request.getPage(), request.getSize());
        List<SystemUserResult> userDOPage = systemUserRoleService.getFilterUserList(page, userInfo.getId(), request.getFilter(), request.getRoleIds());
        if (CollectionUtils.isEmpty(userDOPage)) {
            userDOPage = ListUtils.EMPTY_LIST;
        }
        page.setRecords(userDOPage);
        return ResultVO.success(getUserListByPage(page));
    }

    public UserInfoPageResponse getUserListByPage(Page<SystemUserResult> userDOPage) {
        UserInfoPageResponse userInfoPageResponse = new UserInfoPageResponse();
        PageDTOUtil.setPageResponse(userDOPage, userInfoPageResponse);

        List<UserInfoPageResponse.UserInfoPageResponseDetail> list = userDOPage.getRecords().stream().map(record -> {
            UserInfoPageResponse.UserInfoPageResponseDetail detail = new UserInfoPageResponse.UserInfoPageResponseDetail();
            detail.setAvatar(record.getAvatar());
            detail.setCreateTime(record.getAddTime());
            detail.setUpdateTime(record.getUpdateTime());
            detail.setDesc(record.getDesc());
            detail.setNickname(record.getNickname());
            detail.setUsername(record.getUsername());
            detail.setParentId(record.getAddUser());
            detail.setId(record.getId());
            detail.setRoleId(record.getRoleId());
            detail.setRoleName(record.getRoleName());
            return detail;
        }).collect(Collectors.toList());
        userInfoPageResponse.setList(list);
        return userInfoPageResponse;
    }

    private String createToken(SystemUserEntity userInfo) {
        if (userInfo == null || userInfo.getId() == null) {
            throw new IllegalArgumentException("userInfo or userId is blank");
        }
        SystemLoginSessionEntity loginSession = new SystemLoginSessionEntity();
        loginSession.setUserId(userInfo.getId());
        String token = UserUtils.genToken();
        loginSession.setToken(token);
        loginSession.setNextExpireTime(System.currentTimeMillis() + loginProperties.getExpireTime());
        boolean ret = systemLoginSessionService.save(loginSession);
        if (!ret) {
            log.error("loginsession : {} insert into fail", loginSession);
            throw new RtAdminException(ErrorCode.SYSTEM_ERROR);
        } else {
            return token;
        }
    }

    /**
     * 判断higherId是不是curId的上次/创建者,上上级...
     *
     * @param higherId
     * @param curId
     * @return
     */
    @Override
    public boolean isHigherLevel(Long higherId, Long curId) {
        if (higherId == null || curId == null) {
            return false;
        }
        List<Long> parentUserIdList = getParentUserIdList(curId);
        return parentUserIdList.contains(higherId);
    }

    @Override
    public ResultVO userTree(UserInfo userInfo) {
        UserTreeResponse userTreeResponse = new UserTreeResponse();
        userTreeResponse.setNickname(userInfo.getNickname());
        userTreeResponse.setId(userInfo.getId());
        List<SystemUserEntity> userChildrenList = systemUserService.getUserChain(RtAdminUtils.parentChain(userInfo.getId(), userInfo.getParentChain()));
        if (CollectionUtils.isEmpty(userChildrenList)) {
            return ResultVO.success(userTreeResponse);
        }
        // 已经处理完的id
        Map<Long, UserTreeResponse> handledMap = Maps.newHashMap();
        handledMap.put(userTreeResponse.getId(), userTreeResponse);
        for (SystemUserEntity userEntity : userChildrenList) {
            if (handledMap.get(userEntity.getId()) != null) {
                handledMap.get(userEntity.getId()).setNickname(userEntity.getNickname());
            } else {
                UserTreeResponse curResponse = new UserTreeResponse(userEntity.getId(), userEntity.getNickname());
                handledMap.put(curResponse.getId(), curResponse);
                UserTreeResponse parentResponse = handledMap.get(userEntity.getParentId());
                // 如果父节点没有，先使用id创建父节点
                if (parentResponse == null) {
                    parentResponse = new UserTreeResponse(userEntity.getParentId());
                    parentResponse.setChildren(Lists.newArrayList(curResponse));
                    handledMap.put(parentResponse.getId(), parentResponse);
                } else {
                    if (parentResponse.getChildren() == null) {
                        parentResponse.setChildren(Lists.newArrayList(curResponse));
                    } else {
                        parentResponse.getChildren().add(curResponse);
                    }
                }
            }
        }
        return ResultVO.success(userTreeResponse);
    }


    @Override
    public ResultVO info(UserInfo userInfo) {
        UserBaseInfoResponse userBaseInfoResponse = new UserBaseInfoResponse();
        userBaseInfoResponse.setAvatar(userInfo.getAvatar());
        userBaseInfoResponse.setNickname(userInfo.getNickname());
        userBaseInfoResponse.setSignature(userInfo.getSignature());
        Requires.requireNonNull(userInfo.getParentId(), "parent id");
        SystemUserEntity systemUserEntity = systemUserService.getById(userInfo.getParentId());
        if (!userInfo.getParentId().equals(ADMIN_SUPER_ID)) {
            userBaseInfoResponse.setParentId(systemUserEntity.getId());
            userBaseInfoResponse.setParentNickname(systemUserEntity.getNickname());
        }
        SystemPermissionEntity permissionDO = systemPermissionService.getPermissionById(userInfo.getId());
        if (permissionDO != null) {
            userBaseInfoResponse.setLimit(permissionDO.getLimitDetail());
        }
        return ResultVO.success(userBaseInfoResponse);
    }


    @Override
    public ResultVO updateInfo(UserInfo userInfo, UserBaseInfoRequest request) {
        SystemUserEntity userDO = systemUserService.getById(userInfo.getId());
        userDO.setAvatar(request.getAvatar());
        userDO.setNickname(request.getNickname());
        userDO.setSignature(request.getSignature());
        systemUserService.updateById(userDO);
        return ResultVO.success();
    }

    @Override
    public BufferedImage verifyCode(HttpServletRequest request, HttpServletResponse response) {
        IVerifyCodeManager.VerifyCode<BufferedImage> verifyCode = verifyCodeManage.genCode(IVerifyCodeManager.CodeType.SIMPLE_PICTURE);
        CookieUtils.setCookie(request, response, RtAdminConstant.VERIFY_CODE_COOKIE_KEY, verifyCode.getCodeId().toString());
        return verifyCode.getData();
    }

    @Transactional
    @Override
    public ResultVO demoRegister(@Valid UserRegisterRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        SystemUserEntity userEntityFind = new SystemUserEntity();
        userEntityFind.setUsername(request.getUsername());
        SystemUserEntity systemUserEntity = systemUserService.getOne(new QueryWrapper<>(userEntityFind));
        if (systemUserEntity != null) {
          return ResultVO.error(ErrorCode.USER_EXISTS);
        }

        systemUserEntity = new SystemUserEntity();
        String salt = UserUtils.genSalt();
        systemUserEntity.setPassword(UserUtils.genPassword(request.getPassword(), salt));
        systemUserEntity.setSalt(salt);
        systemUserEntity.setUsername(request.getUsername());

        // 访客用户最高级
        SystemUserEntity systemVisitorEntityFind = new SystemUserEntity();
        systemVisitorEntityFind.setUsername("visitor");
        SystemUserEntity systemVisitorEntity = systemUserService.getOne(new QueryWrapper<>(systemVisitorEntityFind));
        systemUserEntity.setAvatar(systemVisitorEntity.getAvatar());
        systemUserEntity.setParentId(systemVisitorEntity.getId());
        systemUserEntity.setNickname(systemUserEntity.getUsername());
        systemUserEntity.setParentChain(genChildLevelParentChain(systemVisitorEntity.getId(), systemVisitorEntity.getParentChain()));
        systemUserService.save(systemUserEntity);

        // 分配角色和权限: visitor用户创建的第一个角色作为新增用户角色

        SystemRoleEntity roleEntity = systemUserRoleService.getRoleById(systemVisitorEntity.getId());
        SystemRoleEntity systemRoleEntityFind = new SystemRoleEntity();
        systemRoleEntityFind.setParentId(roleEntity.getId());
        SystemRoleEntity systemRoleEntity = systemRoleService.getOne(new QueryWrapper(systemRoleEntityFind));
        Requires.requireNonNull(systemRoleEntity);

        SystemUserRoleEntity systemUserRoleEntity = new SystemUserRoleEntity();
        systemUserRoleEntity.setRoleId(systemRoleEntity.getId());
        systemUserRoleEntity.setUserId(systemUserEntity.getId());
        systemUserRoleService.save(systemUserRoleEntity);


        // 生成token
        String token = createToken(systemUserEntity);
        if (StringUtils.isBlank(token)) {
            log.error("token gen fail");
            return ResultVO.error(ErrorCode.SYSTEM_ERROR);
        }
        UserResponse userResponse = new UserResponse();
        userResponse.setKey(loginProperties.getTokenKey());
        userResponse.setToken(token);

        // 添加用户信息到request,方便拦截使用
        addUserInfo(httpRequest, systemUserEntity);
        return ResultVO.success(userResponse);
    }

    private String genChildLevelParentChain(Long userId, String userParentChain) {
        Requires.requireNonNull(userId, "user id");
        Requires.requireNonBlank(userParentChain, "parent chain");
        return userParentChain + "-" + userId;
    }


    private List<Long> getParentUserIdList(Long userId) {
        Requires.requireNonNull(userId, "user id");
        SystemUserEntity userDO = systemUserService.getById(userId);
        Requires.requireNonNull(userDO, "user");
        String parentChain = userDO.getParentChain();
        return Stream.of(StringUtils.split(parentChain, "-"))
                .map(parentId -> Long.valueOf(parentId)).collect(Collectors.toList());
    }


}
