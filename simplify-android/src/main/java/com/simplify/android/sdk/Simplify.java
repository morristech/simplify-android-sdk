package com.simplify.android.sdk;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.Base64;
import android.util.Log;

import com.google.android.gms.identity.intents.model.UserAddress;
import com.google.android.gms.wallet.FullWallet;
import com.google.android.gms.wallet.MaskedWallet;
import com.google.android.gms.wallet.PaymentMethodToken;
import com.google.android.gms.wallet.WalletConstants;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import io.reactivex.Single;


@SuppressWarnings("unused")
public class Simplify {

    /**
     * A callback interface for handling the Android Pay transaction lifecycle.
     * <p>
     * Used in conjunction with {@link #handleAndroidPayResult(int, int, Intent, AndroidPayCallback)}
     */
    public interface AndroidPayCallback {

        /**
         * Called when a masked wallet is returned from AndroidPay
         *
         * @param maskedWallet A masked wallet object
         */
        void onReceivedMaskedWallet(MaskedWallet maskedWallet);

        /**
         * Called when a full wallet is returned from AndroidPay
         *
         * @param fullWallet A full wallet object
         */
        void onReceivedFullWallet(FullWallet fullWallet);

        /**
         * Called when a user cancels an AndroidPay transaction
         */
        void onAndroidPayCancelled();

        /**
         * Called when an error occurs during an AndroidPay transaction
         *
         * @param errorCode The corresponding error code (see {@link com.google.android.gms.wallet.WalletConstants} for a list of supported errors)
         */
        void onAndroidPayError(int errorCode);
    }

    /**
     * Request code to use with WalletFragment when requesting a {@link MaskedWallet}
     * <p>
     * <b>required if using {@link #handleAndroidPayResult(int, int, Intent, AndroidPayCallback)}</b>
     */
    public static final int REQUEST_CODE_MASKED_WALLET = 74675439;

    /**
     * Request code to use with WalletFragment when requesting a {@link FullWallet}
     * <p>
     * <b>required if using {@link #handleAndroidPayResult(int, int, Intent, AndroidPayCallback)}</b>
     */
    public static final int REQUEST_CODE_FULL_WALLET = 74675440;



    static final String PATTERN_API_KEY = "(?:lv|sb)pb_(.+)";
    static final String LIVE_KEY_PREFIX = "lvpb_";

    String apiKey;
    String androidPayPublicKey;


    /**
     * Creates an instance of the Simplify object
     */
    public Simplify() {
    }

    /**
     * Initializes the SDK with an Simplify public API key
     *
     * @param apiKey A valid Live or Sandbox public API key
     * @return The Simplify instance
     * @throws IllegalArgumentException If invalid API key
     */
    public Simplify setApiKey(String apiKey) {
        if (!validateApiKey(apiKey)) {
            throw new IllegalArgumentException("Invalid api key");
        }

        // store api key
        this.apiKey = apiKey;

        return this;
    }

    /**
     * Gets the Simplify API key
     *
     * @return The API key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Initializes the SDK with the Android Pay public key provided by Simplify.
     * <p>
     * This value is optional and is used internally to encrypt card information during an Android Pay transaction.
     * It can be retrieved from your Simplify account settings and is different from your API key.
     *
     * @param androidPayPublicKey A valid Android Pay public key
     * @return The Simplify instance
     */
    public Simplify setAndroidPayPublicKey(String androidPayPublicKey) {
        this.androidPayPublicKey = androidPayPublicKey;

        return this;
    }

    /**
     * Gets the Android Pay public key
     *
     * @return The public key
     */
    public String getAndroidPayPublicKey() {
        return androidPayPublicKey;
    }

    /**
     * Performs an asynchronous request to the Simplify server to retrieve a card token
     * that can then be used to process a payment
     *
     * @param card     A valid card object
     * @param callback The callback to invoke after the request is complete
     */
    public void createCardToken(Card card, CardToken.Callback callback) {
        // create handler on current thread
        Handler handler = new Handler(msg -> handleCreateCardTokenCallbackMessage(callback, msg.obj));

        new Thread(() -> runCreateCardToken(card, handler)).start();
    }

    /**
     * <p>Builds a Single to retrieve a card token that can then be used to process a payment</p>
     * <p>Does not operate on any particular scheduler</p>
     *
     * @param card A valid card object
     * @return A Single of the CardToken
     */
    public Single<CardToken> createCardToken(Card card) {
        return Single.fromCallable(() -> executeCreateCardToken(card));
    }

    /**
     * Performs an asynchronous request to the Simplify server to retrieve a card token
     * that can then be used to process a payment
     *
     * @param fullWallet A full wallet response from Android Pay
     * @param callback   The callback to invoke after the request is complete
     */
    public void createAndroidPayCardToken(FullWallet fullWallet, CardToken.Callback callback) {
        createCardToken(buildAndroidPayCard(fullWallet), callback);
    }

    /**
     * <p>Builds a Single to retrieve a card token that can then be used to process a payment</p>
     * <p>Does not operate on any particular scheduler</p>
     *
     * @param fullWallet A full wallet response from Android Pay
     * @return A Single of the CardToken
     */
    public Single<CardToken> createAndroidPayCardToken(FullWallet fullWallet) {
        return createCardToken(buildAndroidPayCard(fullWallet));
    }


    /**
     * <p>Convenience method to handle the Android Pay transaction lifecycle. You may use this method within
     * onActivityResult() to delegate the transaction to an {@link AndroidPayCallback}.</p>
     * <pre><code>
     * protected void onActivityResult(int requestCode, int resultCode, Intent data) {
     *
     *     if (mSimplify.handleAndroidPayResult(requestCode, resultCode, data, this)) {
     *         return;
     *     }
     *
     *     // ...
     * }</code></pre>
     *
     * @param requestCode The activity request code
     * @param resultCode  The activity result code
     * @param data        Data returned by activity
     * @param callback    The AndroidPayCallback
     * @return True if handled, False otherwise
     * @throws IllegalArgumentException If callback is null
     */
    public boolean handleAndroidPayResult(int requestCode, int resultCode, Intent data, AndroidPayCallback callback) {
        // a callback is required
        if (callback == null) {
            throw new IllegalArgumentException("AndroidPayCallback can not be null");
        }

        // is the request code recognized?
        if (requestCode != REQUEST_CODE_MASKED_WALLET && requestCode != REQUEST_CODE_FULL_WALLET) {
            return false;
        }

        switch (resultCode) {
            case Activity.RESULT_OK:
                if (data != null) {
                    switch (requestCode) {
                        case REQUEST_CODE_MASKED_WALLET:
                            if (data.hasExtra(WalletConstants.EXTRA_MASKED_WALLET)) {
                                MaskedWallet maskedWallet = data.getParcelableExtra(WalletConstants.EXTRA_MASKED_WALLET);
                                callback.onReceivedMaskedWallet(maskedWallet);
                            }
                            return true;

                        case REQUEST_CODE_FULL_WALLET:
                            if (data.hasExtra(WalletConstants.EXTRA_FULL_WALLET)) {
                                FullWallet fullWallet = data.getParcelableExtra(WalletConstants.EXTRA_FULL_WALLET);
                                callback.onReceivedFullWallet(fullWallet);
                            }
                            return true;
                    }
                }

                break;

            case Activity.RESULT_CANCELED:
                callback.onAndroidPayCancelled();
                break;

            default:
                // retrieve the error code, if available
                int errorCode = -1;
                if (data != null) {
                    errorCode = data.getIntExtra(WalletConstants.EXTRA_ERROR_CODE, -1);
                }

                callback.onAndroidPayError(errorCode);
                break;
        }

        return false;
    }


    boolean validateApiKey(String apiKey) {
        Pattern p = Pattern.compile(PATTERN_API_KEY);
        Matcher m = p.matcher(apiKey);
        if (m.matches()) {
            // parse UUID
            String uuidStr = base64Decode(m.group(1));
            try {
                UUID uuid = UUID.fromString(uuidStr);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        return false;
    }

    /* Isolated for testing/mocking */
    String base64Decode(String str) {
        return new String(Base64.decode(str, Base64.NO_PADDING));
    }

    Card buildAndroidPayCard(FullWallet fullWallet) {
        PaymentMethodToken token = fullWallet.getPaymentMethodToken();
        UserAddress address = fullWallet.getBuyerBillingAddress();

        JsonObject androidPayData = new JsonObject();
        androidPayData.addProperty("publicKey", androidPayPublicKey);
        androidPayData.add("paymentToken", new JsonParser().parse(token.getToken()));

        return new Card()
                .setCardEntryMode(CardEntryMode.ANDROID_PAY_IN_APP)
                .setAndroidPayData(androidPayData)
                .setAddressLine1(address.getAddress1())
                .setAddressLine2(address.getAddress2())
                .setAddressCity(address.getLocality())
                .setAddressState(address.getAdministrativeArea())
                .setAddressZip(address.getPostalCode())
                .setAddressCountry(address.getCountryCode())
                .setCustomer(new Customer()
                        .setName(address.getName())
                        .setEmail(fullWallet.getEmail()));
    }

    /**
     * This is the message handler when the request thread returns a response
     *
     * @param callback
     * @param arg
     * @return
     */
    boolean handleCreateCardTokenCallbackMessage(CardToken.Callback callback, Object arg) {
        if (callback != null) {
            if (arg instanceof Throwable) {
                callback.onError((Throwable) arg);
            } else {
                callback.onSuccess((CardToken) arg);
            }
        }
        return true;
    }

    /**
     * This is the body of the Runnable when executing the request on a new thread
     *
     * @param card
     * @param handler
     */
    void runCreateCardToken(Card card, Handler handler) {
        Message m = handler.obtainMessage();
        try {
            m.obj = executeCreateCardToken(card);
        } catch (Exception e) {
            m.obj = e;
        }

        handler.sendMessage(m);
    }

    CardToken executeCreateCardToken(Card card) throws Exception {
        // build url
        URL url = new URL(getUrl() + Constants.API_PATH_CARDTOKEN);

        // build data
        JsonObject json = new JsonObject();
        json.addProperty("key", apiKey);
        json.add("card", new Gson().toJsonTree(card));
        String data = json.toString();

        // make the call
        Response response = executePost(Simplify.class.getSimpleName(), url, data);

        if (response.hasException()) {
            throw response.getException();
        }

        Gson gson = new Gson();

        if (!response.isOk()) {
            JsonObject el = new JsonParser().parse(response.getPayload()).getAsJsonObject();
            SimplifyError error = gson.fromJson(el.get("error"), SimplifyError.class);
            error.statusCode = response.getStatusCode();

            throw error;
        }

        return gson.fromJson(response.getPayload(), CardToken.class);
    }

    String getUrl() {
        return isLive() ? Constants.API_BASE_LIVE_URL : Constants.API_BASE_SANDBOX_URL;
    }

    boolean isLive() {
        return apiKey != null && apiKey.startsWith(LIVE_KEY_PREFIX);
    }

    Response executePost(String tag, URL url, String data) throws Exception {
        // init ssl context with limiting trust managers
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(null, createTrustManagers(), null);

        // init connection
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (url.getProtocol().startsWith("https")) {
            ((HttpsURLConnection) c).setSSLSocketFactory(context.getSocketFactory());
        }
        c.setConnectTimeout(Constants.CONNECTION_TIMEOUT);
        c.setReadTimeout(Constants.SOCKET_TIMEOUT);
        c.setRequestProperty("User-Agent", buildUserAgent());
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoInput(true);
        c.setDoOutput(true);
        c.setRequestMethod("POST");

        // log request
        logRequest(tag, c, data);

        // write data
        if (data != null) {
            OutputStream os = c.getOutputStream();
            os.write(data.getBytes("UTF-8"));
            os.close();
        }

        c.connect();

        Response response = new Response(c);

        c.disconnect();

        // log response
        logResponse(tag, response);

        return response;
    }

    private String buildUserAgent() {
        return Constants.USER_AGENT + " (API " + Build.VERSION.SDK_INT + "; Device:" + Build.DEVICE + ")";
    }

    TrustManager[] createTrustManagers() {
        try {
            // create and initialize a KeyStore
            KeyStore keyStore = createSSLKeyStore();

            // create a TrustManager that trusts the CA in our KeyStore
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);

            return tmf.getTrustManagers();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new TrustManager[0];
    }

    KeyStore createSSLKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);

        // add our trusted cert to the keystore
        keyStore.setCertificateEntry(Constants.KEYSTORE_CA_ALIAS, readCertificate(Constants.INTERMEDIATE_CA));

        return keyStore;
    }

    Certificate readCertificate(String pemCert) throws CertificateException {
        // add our trusted cert to the keystore
        ByteArrayInputStream is = new ByteArrayInputStream(Base64.decode(pemCert, Base64.DEFAULT));
        return CertificateFactory.getInstance("X.509").generateCertificate(is);
    }

    void logRequest(String tag, HttpURLConnection c, String data) {
        Log.d(tag, "REQUEST: " + c.getRequestMethod() + " " + c.getURL().toString());

        if (data != null) {
            Log.d(tag, "-- Data: " + data);
        }

        // log request headers
        Map<String, List<String>> properties = c.getRequestProperties();
        Set<String> keys = properties.keySet();
        for (String key : keys) {
            List<String> values = properties.get(key);
            for (String value : values) {
                Log.d(tag, "-- " + key + ": " + value);
            }
        }
    }


    void logResponse(String tag, Response response) {
        String log = "RESPONSE: ";

        // log response headers
        Map<String, List<String>> headers = response.getConnection().getHeaderFields();
        Set<String> keys = headers.keySet();

        int i = 0;
        for (String key : keys) {
            List<String> values = headers.get(key);
            for (String value : values) {
                if (i == 0 && key == null) {
                    log += value;

                    String payload = response.getPayload();
                    if (payload.length() > 0) {
                        log += "\n-- Data: " + payload;
                    }
                } else {
                    log += "\n-- " + (key == null ? "" : key + ": ") + value;
                }
                i++;
            }
        }

        String[] parts = log.split("\n");
        for (String part : parts) {
            Log.d(tag, part);
        }
    }
}
