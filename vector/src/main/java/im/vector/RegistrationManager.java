/*
 * Copyright 2017 Vector Creations Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector;

import android.content.Context;
import android.text.TextUtils;

import org.matrix.androidsdk.HomeserverConnectionConfig;
import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.client.LoginRestClient;
import org.matrix.androidsdk.rest.client.ThirdPidRestClient;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.RequestPhoneNumberValidationResponse;
import org.matrix.androidsdk.rest.model.ThreePid;
import org.matrix.androidsdk.rest.model.login.Credentials;
import org.matrix.androidsdk.rest.model.login.LoginFlow;
import org.matrix.androidsdk.rest.model.login.RegistrationFlowResponse;
import org.matrix.androidsdk.rest.model.login.RegistrationParams;
import org.matrix.androidsdk.ssl.CertUtil;
import org.matrix.androidsdk.ssl.Fingerprint;
import org.matrix.androidsdk.ssl.UnrecognizedCertificateException;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.androidsdk.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import im.vector.activity.CommonActivityUtils;

public class RegistrationManager {
    private static final String LOG_TAG = RegistrationManager.class.getSimpleName();

    private static volatile RegistrationManager sInstance;

    private static String ERROR_MISSING_STAGE = "ERROR_MISSING_STAGE";

    // JSON keys used or registration request
    private static final String JSON_KEY_CLIENT_SECRET = "client_secret";
    private static final String JSON_KEY_ID_SERVER = "id_server";
    private static final String JSON_KEY_SID = "sid";
    private static final String JSON_KEY_TYPE = "type";
    private static final String JSON_KEY_THREEPID_CREDS = "threepid_creds";
    private static final String JSON_KEY_SESSION = "session";
    private static final String JSON_KEY_CAPTCHA_RESPONSE = "response";

    private static List<String> VECTOR_SUPPORTED_STAGES = Arrays.asList(
            LoginRestClient.LOGIN_FLOW_TYPE_PASSWORD,
            LoginRestClient.LOGIN_FLOW_TYPE_DUMMY,
            LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY,
            LoginRestClient.LOGIN_FLOW_TYPE_MSISDN,
            LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA);

    private HomeserverConnectionConfig mHsConfig;
    private LoginRestClient mLoginRestClient;
    private ThirdPidRestClient mThirdPidRestClient;

    private RegistrationFlowResponse mRegistrationResponse;

    private final Set<String> mSupportedStages = new HashSet<>();
    private final List<String> mRequiredStages = new ArrayList<>();
    private final List<String> mConditionalOptionalStages = new ArrayList<>();
    private final List<String> mOptionalStages = new ArrayList<>();

    // Params
    private String mUsername;
    private String mPassword;
    private ThreePid mEmail;
    private ThreePid mPhoneNumber;
    private String mCaptchaResponse;
    /*
    * *********************************************************************************************
    * Singleton
    * *********************************************************************************************
    */

    public static RegistrationManager getInstance() {
        if (sInstance == null) {
            sInstance = new RegistrationManager();
        }
        return sInstance;
    }

    private RegistrationManager() {
    }

    /*
    * *********************************************************************************************
    * Public methods
    * *********************************************************************************************
    */

    public void resetSingleton() {
        // Clear all data to allow new registration
        mHsConfig = null;
        mLoginRestClient = null;
        mThirdPidRestClient = null;
        mRegistrationResponse = null;

        mSupportedStages.clear();
        mRequiredStages.clear();
        mOptionalStages.clear();
        mConditionalOptionalStages.clear();

        mUsername = null;
        mPassword = null;
        mEmail = null;
        mPhoneNumber = null;
        mCaptchaResponse = null;
    }

    public void setHsConfig(final HomeserverConnectionConfig hsConfig) {
        mHsConfig = hsConfig;
        mLoginRestClient = null;
        mThirdPidRestClient = null;
    }

    public void setAccountData(final String username, final String password) {
        mUsername = username;
        mPassword = password;
    }

    public void setCaptchaResponse(final String captchaResponse) {
        mCaptchaResponse = captchaResponse;
    }

    public void checkUsernameAvailability(final Context context, final UsernameValidityListener listener) {
        if (getLoginRestClient() != null) {
            final RegistrationParams params = new RegistrationParams();
            params.username = mUsername;

            register(context, params, new InternalRegistrationListener() {
                @Override
                public void onRegistrationSuccess() {
                    listener.onUsernameAvailabilityChecked(true);
                }

                @Override
                public void onRegistrationFailed(String message) {
                    if (TextUtils.equals(MatrixError.USER_IN_USE, message)) {
                        listener.onUsernameAvailabilityChecked(false);
                    } else {
                        listener.onUsernameAvailabilityChecked(true);
                    }
                }
            });
        }
    }

    private Map<String, Object> getThreePidAuthParams(final String clientSecret, final String host,
                                                      final String sid, final String medium) {
        Map<String, Object> authParams = new HashMap<>();
        Map<String, String> pidsCredentialsAuth = new HashMap<>();
        pidsCredentialsAuth.put(JSON_KEY_CLIENT_SECRET, clientSecret);
        pidsCredentialsAuth.put(JSON_KEY_ID_SERVER, host);
        pidsCredentialsAuth.put(JSON_KEY_SID, sid);
        authParams.put(JSON_KEY_TYPE, medium);
        authParams.put(JSON_KEY_THREEPID_CREDS, pidsCredentialsAuth);
        authParams.put(JSON_KEY_SESSION, mRegistrationResponse.session);
        return authParams;
    }

    private Map<String, Object> getCaptchaAuthParams(final String captchaResponse) {
        Map<String, Object> authParams = new HashMap<>();
        authParams.put(JSON_KEY_TYPE, LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA);
        authParams.put(JSON_KEY_CAPTCHA_RESPONSE, captchaResponse);
        authParams.put(JSON_KEY_SESSION, mRegistrationResponse.session);
        return authParams;
    }

    public void attemptRegistration(final Context context, final RegistrationListener listener) {
        final String registrationType;
        if (!TextUtils.isEmpty(mRegistrationResponse.session)) {
            Map<String, Object> authParams;
            if (mPhoneNumber != null && !isCompleted(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN) && !TextUtils.isEmpty(mPhoneNumber.sid)) {
                registrationType = LoginRestClient.LOGIN_FLOW_TYPE_MSISDN;
                authParams = getThreePidAuthParams(mPhoneNumber.clientSecret, mHsConfig.getIdentityServerUri().getHost(), mPhoneNumber.sid, LoginRestClient.LOGIN_FLOW_TYPE_MSISDN);
            } else if (mEmail != null && !isCompleted(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY)) {
                if (TextUtils.isEmpty(mEmail.sid)) {
                    // Email token needs to be requested before doing validation
                    requestValidationToken(mEmail, new ThreePidRequestListener() {
                        @Override
                        public void onThreePidRequested(ThreePid pid) {
                            listener.onWaitingEmailValidation();
                        }
                    });
                    return;
                } else {
                    registrationType = LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY;
                    authParams = getThreePidAuthParams(mEmail.clientSecret, mHsConfig.getIdentityServerUri().getHost(), mEmail.sid, LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY);
                }
            } else if (!TextUtils.isEmpty(mCaptchaResponse) && !isCompleted(LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA)) {
                registrationType = LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA;
                authParams = getCaptchaAuthParams(mCaptchaResponse);
            } else if (mSupportedStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_DUMMY)) {
                registrationType = LoginRestClient.LOGIN_FLOW_TYPE_DUMMY;
                authParams = new HashMap<>();
                authParams.put(JSON_KEY_TYPE, LoginRestClient.LOGIN_FLOW_TYPE_DUMMY);
            } else {
                registrationType = LoginRestClient.LOGIN_FLOW_TYPE_PASSWORD;
                authParams = new HashMap<>();
                authParams.put(JSON_KEY_TYPE, LoginRestClient.LOGIN_FLOW_TYPE_PASSWORD);
            }

            final RegistrationParams params = new RegistrationParams();
            params.username = mUsername;
            params.password = mPassword;
            if (!authParams.isEmpty()) {
                params.auth = authParams;
            }
            params.bind_email = mEmail != null;
            params.bind_msisdn = mPhoneNumber != null;

            register(context, params, new InternalRegistrationListener() {
                @Override
                public void onRegistrationSuccess() {
                    if (TextUtils.equals(registrationType, LoginRestClient.LOGIN_FLOW_TYPE_MSISDN) && mEmail != null) {
                        // An email was entered but was not attached to account
                        listener.onRegistrationSuccess(context.getString(R.string.auth_threepid_warning_message));
                    } else {
                        listener.onRegistrationSuccess(null);
                    }
                }

                @Override
                public void onRegistrationFailed(String message) {
                    if (TextUtils.equals(ERROR_MISSING_STAGE, message)
                            && (mPhoneNumber == null || isCompleted(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN))
                            && (mEmail == null || isCompleted(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY))) {
                        // At this point, only captcha can be the missing stage
                        listener.onWaitingCaptcha();
                    } else {
                        listener.onRegistrationFailed(message);
                    }
                }
            });
        }
    }

    /**
     * Register step after a mail validation.
     * In the registration flow after a mail was validated {@see #startEmailOwnershipValidation},
     * this register request must be performed to reach the next registration step.
     *
     * @param context
     * @param aClientSecret   client secret
     * @param aSid            identity server session ID
     * @param aIdentityServer identity server url
     * @param aSessionId      session ID
     * @param listener
     */
    public void registerAfterEmailValidation(final Context context, final String aClientSecret, final String aSid,
                                             final String aIdentityServer, final String aSessionId,
                                             final RegistrationListener listener) {
        // set session
        if (null != mRegistrationResponse) {
            mRegistrationResponse.session = aSessionId;
        }

        Map<String, String> pidsCredentialsAuth = new HashMap<>();
        pidsCredentialsAuth.put(JSON_KEY_CLIENT_SECRET, aClientSecret);
        pidsCredentialsAuth.put(JSON_KEY_ID_SERVER, CommonActivityUtils.removeUrlScheme(aIdentityServer));
        pidsCredentialsAuth.put(JSON_KEY_SID, aSid);

        Map<String, Object> authParams = new HashMap<>();
        authParams.put(JSON_KEY_TYPE, LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY);
        authParams.put(JSON_KEY_THREEPID_CREDS, pidsCredentialsAuth);
        authParams.put(JSON_KEY_SESSION, aSessionId);

        RegistrationParams registrationParams = new RegistrationParams();
        registrationParams.auth = authParams;
        // Note: username, password and bind_email must not be set in registrationParams

        Log.d(LOG_TAG, "## registerAfterEmailValidation(): ");
        register(context, registrationParams, new InternalRegistrationListener() {
            @Override
            public void onRegistrationSuccess() {
                listener.onRegistrationSuccess(null);
            }

            @Override
            public void onRegistrationFailed(String message) {
                if (TextUtils.equals(ERROR_MISSING_STAGE, message)) {
                    // At this point, only captcha can be the missing stage
                    listener.onWaitingCaptcha();
                } else {
                    listener.onRegistrationFailed(message);
                }
            }
        });
    }

    /**
     * Send a registration request
     *
     * @param context
     * @param params
     * @param listener
     */
    private void register(final Context context, final RegistrationParams params, final InternalRegistrationListener listener) {
        if (getLoginRestClient() != null) {
            mLoginRestClient.register(params, new SimpleApiCallback<Credentials>() {
                @Override
                public void onSuccess(Credentials credentials) {
                    if (TextUtils.isEmpty(credentials.userId)) {
                        listener.onRegistrationFailed("No user id");
                    } else {
                        Collection<MXSession> sessions = Matrix.getMXSessions(context);
                        boolean isDuplicated = false;

                        for (MXSession existingSession : sessions) {
                            Credentials cred = existingSession.getCredentials();
                            isDuplicated |= TextUtils.equals(credentials.userId, cred.userId) && TextUtils.equals(credentials.homeServer, cred.homeServer);
                        }

                        if (!isDuplicated) {
                            mHsConfig.setCredentials(credentials);
                            MXSession session = Matrix.getInstance(context).createSession(mHsConfig);
                            Matrix.getInstance(context).addSession(session);
                        }
                        listener.onRegistrationSuccess();
                    }
                }

                @Override
                public void onNetworkError(final Exception e) {
                    UnrecognizedCertificateException unrecCertEx = CertUtil.getCertificateException(e);
                    if (unrecCertEx != null) {
                        final Fingerprint fingerprint = unrecCertEx.getFingerprint();
                        Log.d(LOG_TAG, "Found fingerprint: SHA-256: " + fingerprint.getBytesAsHexString());

                        UnrecognizedCertHandler.show(mHsConfig, fingerprint, false, new UnrecognizedCertHandler.Callback() {
                            @Override
                            public void onAccept() {
                                register(context, params, listener);
                            }

                            @Override
                            public void onIgnore() {
                                listener.onRegistrationFailed(e.getLocalizedMessage());
                            }

                            @Override
                            public void onReject() {
                                listener.onRegistrationFailed(e.getLocalizedMessage());
                            }
                        });
                    } else {
                        listener.onRegistrationFailed(e.getLocalizedMessage());
                    }
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    listener.onRegistrationFailed(e.getLocalizedMessage());
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    if (TextUtils.equals(e.errcode, MatrixError.USER_IN_USE)) {
                        // user name is already taken, the registration process stops here (new user name should be provided)
                        // ex: {"errcode":"M_USER_IN_USE","error":"User ID already taken."}
                        Log.d(LOG_TAG, "User name is used");
                        listener.onRegistrationFailed(MatrixError.USER_IN_USE);
                    } else if (TextUtils.equals(e.errcode, MatrixError.UNAUTHORIZED)) {
                        //do nothing
                    } else if (null != e.mStatus && e.mStatus == 401) {
                        try {
                            RegistrationFlowResponse registrationFlowResponse = JsonUtils.toRegistrationFlowResponse(e.mErrorBodyAsString);
                            setRegistrationFlowResponse(registrationFlowResponse);
                        } catch (Exception castExcept) {
                            Log.e(LOG_TAG, "JsonUtils.toRegistrationFlowResponse " + castExcept.getLocalizedMessage());
                        }
                        listener.onRegistrationFailed(ERROR_MISSING_STAGE);
                    } else {
                        listener.onRegistrationFailed("");
                    }
                }
            });
        }
    }

    public boolean hasNonSupportedStage() {
        return !VECTOR_SUPPORTED_STAGES.containsAll(mSupportedStages);
    }

    public boolean supportStage(final String stage) {
        return mSupportedStages.contains(stage);
    }

    public boolean canAddThreePid() {
        return (mSupportedStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY) && !isCompleted(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY))
                || (mSupportedStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN) && !isCompleted(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN));
    }

    public boolean isCompleted(final String stage) {
        return mRegistrationResponse != null && mRegistrationResponse.completed != null && mRegistrationResponse.completed.contains(stage);
    }

    public boolean isOptional(final String stage) {
        return mOptionalStages.contains(stage);
    }

    public boolean isRequired(final String stage) {
        return mRequiredStages.contains(stage);
    }

    /**
     * @return true if email is mandatory for registration and not completed yet
     */
    public boolean isEmailRequired() {
        return mRegistrationResponse != null
                && mRequiredStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY)
                && (mRegistrationResponse.completed == null || !mRegistrationResponse.completed.contains(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY));
    }

    /**
     * @return true if phone number is mandatory for registration and not completed yet
     */
    public boolean isPhoneNumberRequired() {
        return mRegistrationResponse != null
                && mRequiredStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN)
                && (mRegistrationResponse.completed == null || !mRegistrationResponse.completed.contains(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN));
    }

    public boolean canSkip() {
        boolean canSkip;
        if (mSupportedStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY)) {
            canSkip = mOptionalStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY);
        } else {
            canSkip = true;
        }

        if (canSkip && mSupportedStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN)) {
            canSkip = mOptionalStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN);
        }
        return canSkip;
    }

    /**
     * Request a validation token for the given phone number
     *
     * @param pid
     * @param listener
     */
    public void requestValidationToken(final ThreePid pid, final ThreePidRequestListener listener) {
        if (getThirdPidRestClient() != null) {
            switch (pid.medium) {
                case ThreePid.MEDIUM_EMAIL:
                    String webAppUrl = "https://vector.im/develop";
                    String nextLink = webAppUrl + "/#/register?client_secret=" + pid.clientSecret;
                    nextLink += "&hs_url=" + mHsConfig.getHomeserverUri().toString();
                    nextLink += "&is_url=" + mHsConfig.getIdentityServerUri().toString();
                    nextLink += "&session_id=" + mRegistrationResponse.session;
                    pid.requestEmailValidationToken(getThirdPidRestClient(), nextLink, new ApiCallback<Void>() {
                        @Override
                        public void onSuccess(Void aVoid) {
                            listener.onThreePidRequested(pid);
                        }

                        @Override
                        public void onNetworkError(final Exception e) {
                            UnrecognizedCertificateException unrecCertEx = CertUtil.getCertificateException(e);
                            if (unrecCertEx != null) {
                                final Fingerprint fingerprint = unrecCertEx.getFingerprint();

                                UnrecognizedCertHandler.show(mHsConfig, fingerprint, false, new UnrecognizedCertHandler.Callback() {
                                    @Override
                                    public void onAccept() {
                                        requestValidationToken(pid, listener);
                                    }

                                    @Override
                                    public void onIgnore() {
                                        listener.onThreePidRequested(pid);
                                    }

                                    @Override
                                    public void onReject() {
                                        listener.onThreePidRequested(pid);
                                    }
                                });
                            } else {
                                listener.onThreePidRequested(pid);
                            }
                        }

                        @Override
                        public void onUnexpectedError(Exception e) {
                            listener.onThreePidRequested(pid);
                        }

                        @Override
                        public void onMatrixError(MatrixError e) {
                            listener.onThreePidRequested(pid);
                        }
                    });
                    break;
                case ThreePid.MEDIUM_MSISDN:
                    pid.requestPhoneNumberValidationToken(getThirdPidRestClient(), null, new ApiCallback<RequestPhoneNumberValidationResponse>() {
                        @Override
                        public void onSuccess(RequestPhoneNumberValidationResponse response) {
                            mPhoneNumber = pid;
                            listener.onThreePidRequested(pid);
                        }

                        @Override
                        public void onNetworkError(final Exception e) {
                            UnrecognizedCertificateException unrecCertEx = CertUtil.getCertificateException(e);
                            if (unrecCertEx != null) {
                                final Fingerprint fingerprint = unrecCertEx.getFingerprint();

                                UnrecognizedCertHandler.show(mHsConfig, fingerprint, false, new UnrecognizedCertHandler.Callback() {
                                    @Override
                                    public void onAccept() {
                                        requestValidationToken(pid, listener);
                                    }

                                    @Override
                                    public void onIgnore() {
                                        listener.onThreePidRequested(pid);
                                    }

                                    @Override
                                    public void onReject() {
                                        listener.onThreePidRequested(pid);
                                    }
                                });
                            } else {
                                listener.onThreePidRequested(pid);
                            }
                        }

                        @Override
                        public void onUnexpectedError(Exception e) {
                            listener.onThreePidRequested(pid);
                        }

                        @Override
                        public void onMatrixError(MatrixError e) {
                            listener.onThreePidRequested(pid);
                        }
                    });
                    break;
            }
        }
    }

    public void submitValidationToken(final String token, final ThreePid pid, final ThreePidValidationListener listener) {
        if (getThirdPidRestClient() != null) {
            pid.submitValidationToken(getThirdPidRestClient(), token, pid.clientSecret, pid.sid, new ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean isSuccess) {
                    listener.onThreePidValidated(isSuccess);
                }

                @Override
                public void onNetworkError(Exception e) {
                    listener.onThreePidValidated(false);
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    listener.onThreePidValidated(false);
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    listener.onThreePidValidated(false);
                }
            });
        }
    }

    public String getCaptchaPublicKey() {
        String publicKey = null;
        if (null != mRegistrationResponse.params) {
            Object recaptchaParamsAsVoid = mRegistrationResponse.params.get(LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA);

            if (null != recaptchaParamsAsVoid) {
                try {
                    Map<String, String> recaptchaParams = (Map<String, String>) recaptchaParamsAsVoid;
                    publicKey = recaptchaParams.get("public_key");

                } catch (Exception e) {
                    Log.e(LOG_TAG, "JsonUtils.recaptchaParams " + e.getLocalizedMessage());
                }
            }
        }
        return publicKey;
    }

    public void addEmailThreePid(final String email) {
        mEmail = new ThreePid(email, ThreePid.MEDIUM_EMAIL);
    }

    public void addPhoneNumberThreePid(final String phoneNumber, final String countryCode, final ThreePidRequestListener listener) {
        final ThreePid pid = new ThreePid(phoneNumber, countryCode, ThreePid.MEDIUM_MSISDN);
        requestValidationToken(pid, listener);
    }

    public void clearThreePid() {
        mEmail = null;
        mPhoneNumber = null;
    }

    /*
    * *********************************************************************************************
    * Private methods
    * *********************************************************************************************
    */

    /**
     * Get a login rest client
     *
     * @return login rest client
     */
    private LoginRestClient getLoginRestClient() {
        if (mLoginRestClient == null && mHsConfig != null) {
            mLoginRestClient = new LoginRestClient(mHsConfig);
        }
        return mLoginRestClient;
    }

    /**
     * Get a third pid rest client
     *
     * @return third pid rest client
     */
    private ThirdPidRestClient getThirdPidRestClient() {
        if (mThirdPidRestClient == null && mHsConfig != null) {
            mThirdPidRestClient = new ThirdPidRestClient(mHsConfig);
        }
        return mThirdPidRestClient;
    }

    private void setRegistrationFlowResponse(final RegistrationFlowResponse registrationFlowResponse) {
        if (registrationFlowResponse != null) {
            mRegistrationResponse = registrationFlowResponse;
            analyzeRegistrationStages(registrationFlowResponse);
        }
    }

    /**
     * Analyze the flows stages
     *
     * @param newFlowResponse
     */
    private void analyzeRegistrationStages(final RegistrationFlowResponse newFlowResponse) {
        final Set<String> supportedStages = new HashSet<>();

        boolean canCaptchaBeMissing = false;
        boolean canEmailBeMissing = false;
        boolean canPhoneBeMissing = false;
        boolean canThreePidBeMissing = false;
        for (LoginFlow loginFlow : newFlowResponse.flows) {
            supportedStages.addAll(loginFlow.stages);

            if (!loginFlow.stages.contains(LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA)) {
                canCaptchaBeMissing = true;
            }

            if (!loginFlow.stages.contains(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN)) {
                canPhoneBeMissing = true;
                if (!loginFlow.stages.contains(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY)) {
                    canThreePidBeMissing = true;
                }
            }

            if (!loginFlow.stages.contains(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY)) {
                canEmailBeMissing = true;
            }
        }

        mSupportedStages.clear();
        mSupportedStages.addAll(supportedStages);

        final List<String> requiredStages = new ArrayList<>();
        final List<String> conditionalStages = new ArrayList<>();
        final List<String> optionalStages = new ArrayList<>();

        // Check if captcha is required/optional
        if (supportedStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA)) {
            if (canCaptchaBeMissing) {
                optionalStages.add(LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA);
            } else {
                requiredStages.add(LoginRestClient.LOGIN_FLOW_TYPE_RECAPTCHA);
            }
        }

        if (supportedStages.containsAll(Arrays.asList(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY, LoginRestClient.LOGIN_FLOW_TYPE_MSISDN))
                && !canThreePidBeMissing && canPhoneBeMissing && canEmailBeMissing) {
            // Both are supported and at least is required
            conditionalStages.add(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY);
            conditionalStages.add(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN);
        } else {
            if (supportedStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY)) {
                if (canEmailBeMissing) {
                    optionalStages.add(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY);
                } else {
                    requiredStages.add(LoginRestClient.LOGIN_FLOW_TYPE_EMAIL_IDENTITY);
                }
            }
            if (supportedStages.contains(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN)) {
                if (canPhoneBeMissing) {
                    optionalStages.add(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN);
                } else {
                    requiredStages.add(LoginRestClient.LOGIN_FLOW_TYPE_MSISDN);
                }
            }
        }

        mRequiredStages.clear();
        mConditionalOptionalStages.clear();
        mOptionalStages.clear();

        mRequiredStages.addAll(requiredStages);
        mConditionalOptionalStages.addAll(conditionalStages);
        mOptionalStages.addAll(optionalStages);
    }

    /*
    * *********************************************************************************************
    * Private listeners
    * *********************************************************************************************
    */

    private interface InternalRegistrationListener {
        void onRegistrationSuccess();

        void onRegistrationFailed(String message);
    }

    /*
    * *********************************************************************************************
    * Public listeners
    * *********************************************************************************************
    */

    public interface ThreePidRequestListener {
        void onThreePidRequested(ThreePid pid);
    }

    public interface ThreePidValidationListener {
        void onThreePidValidated(boolean isSuccess);
    }

    public interface UsernameValidityListener {
        void onUsernameAvailabilityChecked(boolean isAvailable);
    }

    public interface RegistrationListener {
        void onRegistrationSuccess(String warningMessage);

        void onRegistrationFailed(String message);

        void onWaitingEmailValidation();

        void onWaitingCaptcha();
    }
}
