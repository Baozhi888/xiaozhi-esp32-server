package xiaozhi.modules.security.controller;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.TokenDTO;
import xiaozhi.common.user.UserDetail;
import xiaozhi.common.utils.Result;
import xiaozhi.common.validator.AssertUtils;
import xiaozhi.common.validator.ValidatorUtils;
import xiaozhi.modules.security.dto.LoginDTO;
import xiaozhi.modules.security.dto.SmsVerificationDTO;
import xiaozhi.modules.security.password.PasswordUtils;
import xiaozhi.modules.security.service.CaptchaService;
import xiaozhi.modules.security.service.SysUserTokenService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.common.utils.SM2Utils;
import org.apache.commons.lang3.StringUtils;
import xiaozhi.modules.sys.dto.PasswordDTO;
import xiaozhi.modules.sys.dto.RetrievePasswordDTO;
import xiaozhi.modules.sys.dto.SysUserDTO;
import xiaozhi.modules.sys.service.SysDictDataService;
import xiaozhi.modules.sys.service.SysParamsService;
import xiaozhi.modules.sys.service.SysUserService;
import xiaozhi.modules.sys.vo.SysDictDataItem;

/**
 * 登录控制层
 */
@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/user")
@Tag(name = "登录管理")
public class LoginController {
    private final SysUserService sysUserService;
    private final SysUserTokenService sysUserTokenService;
    private final CaptchaService captchaService;
    private final SysParamsService sysParamsService;
    private final SysDictDataService sysDictDataService;

    @GetMapping("/captcha")
    @Operation(summary = "验证码")
    public void captcha(HttpServletResponse response, String uuid) throws IOException {
        // uuid不能为空
        AssertUtils.isBlank(uuid, ErrorCode.IDENTIFIER_NOT_NULL);
        // 生成验证码
        captchaService.create(response, uuid);
    }

    @PostMapping("/smsVerification")
    @Operation(summary = "短信验证码")
    public Result<Void> smsVerification(@RequestBody SmsVerificationDTO dto) {
        Boolean isMobileRegister = sysParamsService
                .getValueObject(Constant.SysMSMParam.SERVER_ENABLE_MOBILE_REGISTER.getValue(), Boolean.class);
        if (!isMobileRegister) {
            throw new RenException(ErrorCode.MOBILE_REGISTER_DISABLED);
        }
        // 发送短信验证码
        captchaService.sendSMSValidateCode(dto.getPhone());
        return new Result<>();
    }

    @PostMapping("/login")
    @Operation(summary = "登录")
    public Result<TokenDTO> login(@RequestBody LoginDTO login) {
        String password = login.getPassword();
        
        // 获取SM2私钥
        String privateKeyStr = sysParamsService.getValue(Constant.SM2_PRIVATE_KEY, true);
        if (StringUtils.isBlank(privateKeyStr)) {
            throw new RenException(ErrorCode.SM2_KEY_NOT_CONFIGURED);
        }
        
        // 使用SM2私钥解密密码
        String decryptedContent;
        try {
            decryptedContent = SM2Utils.decrypt(privateKeyStr, password);
        } catch (Exception e) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
        
        // 分离验证码和密码：前5位是验证码，后面是密码
        if (decryptedContent.length() > 5) {
            String embeddedCaptcha = decryptedContent.substring(0, 5);
            String actualPassword = decryptedContent.substring(5);
            
            // 验证嵌入的验证码是否正确
            boolean embeddedCaptchaValid = captchaService.validate(login.getCaptchaId(), embeddedCaptcha, true);
            if (!embeddedCaptchaValid) {
                throw new RenException(ErrorCode.SMS_CAPTCHA_ERROR);
            }
            
            login.setPassword(actualPassword);
        } else {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
        
        // 按照用户名获取用户
        SysUserDTO userDTO = sysUserService.getByUsername(login.getUsername());
        // 判断用户是否存在
        if (userDTO == null) {
            throw new RenException(ErrorCode.ACCOUNT_PASSWORD_ERROR);
        }
        // 判断密码是否正确，不一样则进入if
        if (!PasswordUtils.matches(login.getPassword(), userDTO.getPassword())) {
            throw new RenException(ErrorCode.ACCOUNT_PASSWORD_ERROR);
        }
        return sysUserTokenService.createToken(userDTO.getId());
    }
    


    @PostMapping("/register")
    @Operation(summary = "注册")
    public Result<Void> register(@RequestBody LoginDTO login) {
        if (!sysUserService.getAllowUserRegister()) {
            throw new RenException(ErrorCode.USER_REGISTER_DISABLED);
        }
        
        String password = login.getPassword();
        
        // SM2加密
        String privateKeyStr;
        try {
            // 获取SM2私钥
            privateKeyStr = sysParamsService.getValue(Constant.SM2_PRIVATE_KEY, true);
            if (StringUtils.isBlank(privateKeyStr)) {
                throw new RenException(ErrorCode.SM2_KEY_NOT_CONFIGURED);
            }
        } catch (Exception e) {
            throw new RenException(ErrorCode.SM2_KEY_NOT_CONFIGURED);
        }
        
        String decryptedContent;
        try {
            // 使用SM2私钥解密密码
            decryptedContent = SM2Utils.decrypt(privateKeyStr, password);
        } catch (Exception e) {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
        
        // 分离验证码和密码：前5位是验证码，后面是密码
        if (decryptedContent.length() > 5) {
            String embeddedCaptcha = decryptedContent.substring(0, 5);
            String actualPassword = decryptedContent.substring(5);
            
            // 验证嵌入的验证码是否正确
            boolean embeddedCaptchaValid = captchaService.validate(login.getCaptchaId(), embeddedCaptcha, true);
            if (!embeddedCaptchaValid) {
                throw new RenException(ErrorCode.SMS_CAPTCHA_ERROR);
            }
            
            login.setPassword(actualPassword);
        } else {
            throw new RenException(ErrorCode.SM2_DECRYPT_ERROR);
        }
        
        // 是否开启手机注册
        Boolean isMobileRegister = sysParamsService
                .getValueObject(Constant.SysMSMParam.SERVER_ENABLE_MOBILE_REGISTER.getValue(), Boolean.class);
        boolean validate;
        if (isMobileRegister) {
            // 验证用户是否是手机号码
            boolean validPhone = ValidatorUtils.isValidPhone(login.getUsername());
            if (!validPhone) {
                throw new RenException(ErrorCode.USERNAME_NOT_PHONE);
            }
            // 验证短信验证码是否正常
            validate = captchaService.validateSMSValidateCode(login.getUsername(), login.getMobileCaptcha(), false);
            if (!validate) {
                throw new RenException(ErrorCode.SMS_CODE_ERROR);
            }
        }

        // 按照用户名获取用户
        SysUserDTO userDTO = sysUserService.getByUsername(login.getUsername());
        if (userDTO != null) {
            throw new RenException(ErrorCode.PHONE_ALREADY_REGISTERED);
        }
        userDTO = new SysUserDTO();
        userDTO.setUsername(login.getUsername());
        userDTO.setPassword(login.getPassword());
        sysUserService.save(userDTO);
        return new Result<>();
    }

    @GetMapping("/info")
    @Operation(summary = "用户信息获取")
    public Result<UserDetail> info() {
        UserDetail user = SecurityUser.getUser();
        Result<UserDetail> result = new Result<>();
        result.setData(user);
        return result;
    }

    @PutMapping("/change-password")
    @Operation(summary = "修改用户密码")
    public Result<?> changePassword(@RequestBody PasswordDTO passwordDTO) {
        // 判断非空
        ValidatorUtils.validateEntity(passwordDTO);
        Long userId = SecurityUser.getUserId();
        sysUserTokenService.changePassword(userId, passwordDTO);
        return new Result<>();
    }

    @PutMapping("/retrieve-password")
    @Operation(summary = "找回密码")
    public Result<?> retrievePassword(@RequestBody RetrievePasswordDTO dto) {
        // 是否开启手机注册
        Boolean isMobileRegister = sysParamsService
                .getValueObject(Constant.SysMSMParam.SERVER_ENABLE_MOBILE_REGISTER.getValue(), Boolean.class);
        if (!isMobileRegister) {
            throw new RenException(ErrorCode.RETRIEVE_PASSWORD_DISABLED);
        }
        // 判断非空
        ValidatorUtils.validateEntity(dto);
        // 验证用户是否是手机号码
        boolean validPhone = ValidatorUtils.isValidPhone(dto.getPhone());
        if (!validPhone) {
            throw new RenException(ErrorCode.PHONE_FORMAT_ERROR);
        }

        // 按照用户名获取用户
        SysUserDTO userDTO = sysUserService.getByUsername(dto.getPhone());
        if (userDTO == null) {
            throw new RenException(ErrorCode.PHONE_NOT_REGISTERED);
        }
        // 验证短信验证码是否正常
        boolean validate = captchaService.validateSMSValidateCode(dto.getPhone(), dto.getCode(), false);
        // 判断是否通过验证
        if (!validate) {
            throw new RenException(ErrorCode.SMS_CODE_ERROR);
        }

        sysUserService.changePasswordDirectly(userDTO.getId(), dto.getPassword());
        return new Result<>();
    }

    @GetMapping("/pub-config")
    @Operation(summary = "公共配置")
    public Result<Map<String, Object>> pubConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("enableMobileRegister", sysParamsService
                .getValueObject(Constant.SysMSMParam.SERVER_ENABLE_MOBILE_REGISTER.getValue(), Boolean.class));
        config.put("version", Constant.VERSION);
        config.put("year", "©" + Calendar.getInstance().get(Calendar.YEAR));
        config.put("allowUserRegister", sysUserService.getAllowUserRegister());
        List<SysDictDataItem> list = sysDictDataService.getDictDataByType(Constant.DictType.MOBILE_AREA.getValue());
        config.put("mobileAreaList", list);
        config.put("beianIcpNum", sysParamsService.getValue(Constant.SysBaseParam.BEIAN_ICP_NUM.getValue(), true));
        config.put("beianGaNum", sysParamsService.getValue(Constant.SysBaseParam.BEIAN_GA_NUM.getValue(), true));
        config.put("name", sysParamsService.getValue(Constant.SysBaseParam.SERVER_NAME.getValue(), true));
        
        // SM2公钥
        String publicKey = sysParamsService.getValue(Constant.SM2_PUBLIC_KEY, true);
        if (StringUtils.isBlank(publicKey)) {
            throw new RenException(ErrorCode.SM2_KEY_NOT_CONFIGURED);
        }
        config.put("sm2PublicKey", publicKey);

        return new Result<Map<String, Object>>().ok(config);
    }
}