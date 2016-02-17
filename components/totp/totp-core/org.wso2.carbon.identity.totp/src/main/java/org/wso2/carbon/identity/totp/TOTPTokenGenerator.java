/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.totp;

import org.apache.axis2.context.MessageContext;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.core.util.CryptoException;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.mgt.*;
import org.wso2.carbon.identity.mgt.config.Config;
import org.wso2.carbon.identity.mgt.config.ConfigBuilder;
import org.wso2.carbon.identity.mgt.config.ConfigType;
import org.wso2.carbon.identity.mgt.config.StorageType;
import org.wso2.carbon.identity.mgt.dto.NotificationDataDTO;
import org.wso2.carbon.identity.mgt.mail.DefaultEmailSendingModule;
import org.wso2.carbon.identity.mgt.mail.Notification;
import org.wso2.carbon.identity.mgt.mail.NotificationBuilder;
import org.wso2.carbon.identity.mgt.mail.NotificationData;
import org.wso2.carbon.identity.totp.exception.TOTPException;
import org.wso2.carbon.identity.totp.util.TOTPUtil;
import org.wso2.carbon.idp.mgt.IdentityProviderManagementException;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;


/**
 * TOTP Token generator class.
 */
public class TOTPTokenGenerator {

    private static Log log = LogFactory.getLog(TOTPTokenGenerator.class);
    private static volatile TOTPTokenGenerator instance;
    private static final String USER_NAME = "username";
    private static final String TOTP_TOKEN = "totp-token";

    private TOTPTokenGenerator() {
    }

    ;

    /**
     * Singleton method to get instance of TOTPTokenGenerator.
     *
     * @return instance of TOTPTokenGenerator
     */
    public static TOTPTokenGenerator getInstance() {
        if (instance == null) {
            synchronized (TOTPTokenGenerator.class) {
                if (instance == null) {
                    instance = new TOTPTokenGenerator();
                }
            }
        }
        return instance;
    }

    /**
     * Generate TOTP token for a locally stored user.
     *
     * @param username username of the user
     * @return TOTP token as a String
     * @throws org.wso2.carbon.identity.totp.exception.TOTPException
     */
    public String generateTOTPTokenLocal(String username)
            throws TOTPException {
        long token = 0;
        if (username != null) {
            UserRealm userRealm;
            try {
                String tenantDomain = MultitenantUtils.getTenantDomain(username);
                int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
                RealmService realmService = IdentityTenantUtil.getRealmService();
                userRealm = realmService.getTenantUserRealm(tenantId);
                username = MultitenantUtils.getTenantAwareUsername(String.valueOf(username));

                if (userRealm != null) {
                    String secretKey = TOTPUtil.decrypt(userRealm.getUserStoreManager().getUserClaimValue(username, Constants.SECRET_KEY_CLAIM_URL, null));
                    String email = userRealm.getUserStoreManager().getUserClaimValue(username, "http://wso2.org/claims/emailaddress", null);

                    byte[] secretkey;
                    String encoding;
                    encoding = TOTPUtil.getEncodingMethod();
                    if (Constants.BASE32.equals(encoding)) {
                        Base32 codec32 = new Base32();
                        secretkey = codec32.decode(secretKey);
                    } else {
                        Base64 code64 = new Base64();
                        secretkey = code64.decode(secretKey);
                    }

                    token = getCode(secretkey, getTimeIndex());
                    sendNotification(username, Long.toString(token), email);
                    if (log.isDebugEnabled()) {
                        log.debug("Token is sent to via email to the user : " + username);
                    }
                } else {
                    throw new TOTPException("Cannot find the user realm for the given tenant domain : " + CarbonContext
                            .getThreadLocalCarbonContext().getTenantDomain());
                }
            } catch (IdentityApplicationManagementException e) {
                log.error("Error when fetching the encoding method", e);
            } catch (IdentityProviderManagementException e) {
                log.error("Error when getting the resident IDP", e);
            } catch (UserStoreException e) {
                throw new TOTPException("TOTPTokenGenerator failed while trying to access userRealm of the user : " +
                        username, e);
            } catch (NoSuchAlgorithmException e) {
                throw new TOTPException("TOTPTokenGenerator can't find the configured hashing algorithm", e);
            } catch (InvalidKeyException e) {
                throw new TOTPException("Secret key is not valid", e);
            } catch (CryptoException e) {
                throw new TOTPException("Error while decrypting the key", e);
            }
        }
        return Long.toString(token);
    }

    /**
     * Generate TOTP token for a given Secretkey
     *
     * @param secretKey Secret key
     * @return TOTP token as a string
     * @throws org.wso2.carbon.identity.totp.exception.TOTPException
     */

    public String generateTOTPToken(String secretKey) throws TOTPException {
        long token;

        byte[] secretkey;
        String encoding;
        try {
            encoding = TOTPUtil.getEncodingMethod();
            if ("Base32".equals(encoding)) {
                Base32 codec32 = new Base32();
                secretkey = codec32.decode(secretKey);
            } else {
                Base64 code64 = new Base64();
                secretkey = code64.decode(secretKey);
            }
            token = getCode(secretkey, getTimeIndex());
        } catch (IdentityApplicationManagementException e) {
            throw new TOTPException("Error when fetching the encoding method", e);
        } catch (IdentityProviderManagementException e) {
            throw new TOTPException("Error when getting the resident IDP", e);
        } catch (NoSuchAlgorithmException e) {
            throw new TOTPException("TOTPTokenGenerator can't find the configured hashing algorithm", e);
        } catch (InvalidKeyException e) {
            throw new TOTPException("Secret key is not valid", e);
        }
        return Long.toString(token);
    }

    /**
     * Create the TOTP token for a given secret key and time index
     *
     * @param secret    Secret key
     * @param timeIndex Number of Time elapse from the unix epoch time
     * @return TOTP token value as a long
     * @throws java.security.NoSuchAlgorithmException
     * @throws java.security.InvalidKeyException
     */
    private long getCode(byte[] secret, long timeIndex)
            throws NoSuchAlgorithmException, InvalidKeyException {
        SecretKeySpec signKey = new SecretKeySpec(secret, "HmacSHA1");
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(timeIndex);
        byte[] timeBytes = buffer.array();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(signKey);
        byte[] hash = mac.doFinal(timeBytes);
        int offset = hash[19] & 0xf;
        long truncatedHash = hash[offset] & 0x7f;
        for (int i = 1; i < 4; i++) {
            truncatedHash <<= 8;
            truncatedHash |= hash[offset + i] & 0xff;
        }
        truncatedHash %= 1000000;
        return truncatedHash;
    }

    /**
     * Get Time steps from unix epoch time.
     *
     * @return
     */
    private static long getTimeIndex() {
        long timeStep = Constants.DEFAULT_TIME_STEP_SIZE;
        try {
            timeStep = TOTPUtil.getTimeStepSize();
        } catch (IdentityApplicationManagementException e) {
            log.error("Error when reading the tenant time step size", e);
        } catch (IdentityProviderManagementException e) {
            log.error("Error when getting the resident IDP", e);
        }
        return System.currentTimeMillis() / 1000 / timeStep;
    }


    private void sendNotification(String username, String token, String email) throws TOTPException {
        NotificationSender notificationSender = new NotificationSender();
        NotificationDataDTO notificationData = new NotificationDataDTO();
        Notification emailNotification;
        NotificationData emailNotificationData = new NotificationData();
        ConfigBuilder configBuilder = ConfigBuilder.getInstance();
        String tenantDomain = MultitenantUtils.getTenantDomain(username);
        int tenantId = IdentityTenantUtil.getTenantId(tenantDomain);
        String emailTemplate;
        Config config;

        if (MessageContext.getCurrentMessageContext() != null &&
                MessageContext.getCurrentMessageContext().getProperty(
                        MessageContext.TRANSPORT_HEADERS) != null) {
            System.out.println("not null 1212112121212112121211212");
            notificationData.setTransportHeaders(new HashMap(
                    (Map) MessageContext.getCurrentMessageContext().getProperty(
                            MessageContext.TRANSPORT_HEADERS)));
        }

        try {
            config = configBuilder.loadConfiguration(ConfigType.EMAIL,
                    StorageType.REGISTRY, tenantId);
        } catch (IdentityMgtConfigException e) {
            throw new TOTPException("Error occurred while loading email templates for user : "
                    + username, e);
        }

        emailNotificationData.setTagData(USER_NAME, username);
        emailNotificationData.setTagData(TOTP_TOKEN, token);
        emailNotificationData.setSendTo(email);
        emailTemplate = config.getProperty(Constants.EMAIL_TEMPLATE_NMAME);

        try {
            emailNotification = NotificationBuilder.createNotification("EMAIL", emailTemplate, emailNotificationData);
        } catch (IdentityMgtServiceException e) {
            throw new TOTPException("Error occurred while creating notification from email template : "
                            + emailTemplate, e);
        }
        notificationData.setNotificationAddress(email);
        NotificationSendingModule module = new DefaultEmailSendingModule();

        if (IdentityMgtConfig.getInstance().isNotificationInternallyManaged()) {
            module.setNotificationData(notificationData);
            module.setNotification(emailNotification);
            notificationSender.sendNotification(module);
            notificationData.setNotificationSent(true);
        } else {
            notificationData.setNotificationSent(false);
            notificationData.setNotificationCode(token);
        }
    }
}
