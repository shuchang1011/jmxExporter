/*
 * Copyright(C) 2013 Agree Corporation. All rights reserved.
 *
 * Contributors:
 *     Agree Corporation - initial API and implementation
 */
package cn.com.agree.eureka.util;

import org.apache.http.NameValuePair;
import org.apache.http.NoHttpResponseException;
import org.apache.http.ParseException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class HttpClient {

    private static final int DEFAULT_CONNECT_TIMEOUT = 5000;
    private static final int DEFAULT_CONNECTION_REQUEST_TIMEOUT = 3000;
    private static final int DEFAULT_SOCKET_TIMEOUT = 5000;
    private static final String CHARSET = "UTF-8";

    public static void sendGet(String url, Map<String, Object> params) throws Exception {
        sendGet(url, params, null);
    }

    public static void sendGet(String url, Map<String, Object> params, IHttpResponseHandler handler) throws Exception {
        sendGet(url, params, handler, -1, -1, -1);
    }

    /**
     * @param url 请求url
     * @param params  参数
     * @param connectTimeout  连接超时
     * @param connectionRequestTimeout  请求超时
     * @throws Exception 异常
     */
    public static void sendGet(String url, Map<String, Object> params, int connectTimeout, int connectionRequestTimeout)
            throws Exception {
        sendGet(url, params, null, connectTimeout, connectionRequestTimeout, -1);
    }

    public static void sendGet(String url, Map<String, Object> params, IHttpResponseHandler handler, int connectTimeout,
                               int connectionRequestTimeout, int socketTimeout) throws Exception {
        send(RequestType.GET, url, params, handler, connectTimeout, connectionRequestTimeout, socketTimeout);
    }

    public static void sendPost(String url, Map<String, Object> params) throws Exception {
        sendPost(url, params, null);
    }

    public static void sendPost(String url, Map<String, Object> params, IHttpResponseHandler handler) throws Exception {
        sendPost(url, params, handler, -1, -1, -1);
    }

    public static void sendPost(String url, Map<String, Object> params, int connectTimeout,
                                int connectionRequestTimeout) throws Exception {
        sendPost(url, params, null, connectTimeout, connectionRequestTimeout, -1);
    }

    public static void sendPost(String url, Map<String, Object> params, IHttpResponseHandler handler,
                                int connectTimeout, int connectionRequestTimeout, int socketTimeout) throws Exception {
        send(RequestType.POST, url, params, handler, connectTimeout, connectionRequestTimeout, socketTimeout);
    }

    /**
     * 发送请求
     *
     * @param requestType              请求类型（GET/POST）
     * @param url                      请求URL
     * @param params                   请求参数
     * @param handler                  响应处理器（为 null 时表示不接收响应）
     * @param connectTimeout           连接超时时间（单位：毫秒）
     * @param connectionRequestTimeout 获取连接超时时间（单位：毫秒）
     * @param socketTimeout            读超时时间（单位：毫秒）
     * @throws Exception 请求-响应-处理 过程中发生的异常
     */
    private static void send(RequestType requestType, String url, Map<String, Object> params,
                             IHttpResponseHandler handler, int connectTimeout, int connectionRequestTimeout, int socketTimeout)
            throws Exception {

        if (connectTimeout < 0) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (connectionRequestTimeout < 0) {
            connectionRequestTimeout = DEFAULT_CONNECTION_REQUEST_TIMEOUT;
        }
        if (handler == null) {
            socketTimeout = 1;
        } else if (socketTimeout < 0) {
            socketTimeout = DEFAULT_SOCKET_TIMEOUT;
        }

        HttpRequestBase request;
        switch (requestType) {
            case GET:
                request = generateGetRequest(url, params, connectTimeout, connectionRequestTimeout, socketTimeout);
                break;
            case POST:
                request = generatePostRequest(url, params, connectTimeout, connectionRequestTimeout, socketTimeout);
                break;
            default:
                return;
        }
        request.setConfig(RequestConfig.custom().setConnectTimeout(connectTimeout)
                .setConnectionRequestTimeout(connectionRequestTimeout).setSocketTimeout(socketTimeout).build());

        try (CloseableHttpClient httpClient = newHttpClient()) {
            if (handler != null) {
                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    handler.handle(response);
                }
            } else {
                try {
                    httpClient.execute(request);
                } catch (SocketTimeoutException e) {
                    // ignore
                }
            }
        }
    }

    private static HttpRequestBase generateGetRequest(String url, Map<String, Object> params, int connectTimeout,
                                                      int connectionRequestTimeout, int socketTimeout) throws ParseException, IOException {

        if (params != null && !params.isEmpty()) {

            List<NameValuePair> pairs = new ArrayList<>(params.size());

            for (String key : params.keySet()) {
                pairs.add(new BasicNameValuePair(key, params.get(key).toString()));
            }
            url += "?" + EntityUtils.toString(new UrlEncodedFormEntity(pairs), CHARSET);
        }

        return new HttpGet(url);
    }

    private static HttpRequestBase generatePostRequest(String url, Map<String, Object> params, int connectTimeout,
                                                       int connectionRequestTimeout, int socketTimeout) throws UnsupportedEncodingException {

        List<NameValuePair> pairs = null;
        if (params != null && !params.isEmpty()) {
            pairs = new ArrayList<>(params.size());
            for (String key : params.keySet()) {
                pairs.add(new BasicNameValuePair(key, params.get(key).toString()));
            }
        }
        HttpPost httpPost = new HttpPost(url);
        if (pairs != null && pairs.size() > 0) {
            httpPost.setEntity(new UrlEncodedFormEntity(pairs, CHARSET));
        }

        return httpPost;
    }

    private static CloseableHttpClient newHttpClient() {
        RequestConfig config = RequestConfig.custom().setConnectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .setSocketTimeout(DEFAULT_CONNECTION_REQUEST_TIMEOUT).build();
        return HttpClientBuilder.create().setDefaultRequestConfig(config).setRetryHandler(
                (exception, executionCount, context) -> (executionCount <= 3
                        && exception instanceof NoHttpResponseException)).build();
    }

    private enum RequestType {
        /**
         * get,post
         */
        GET, POST
    }

    public interface IHttpResponseHandler {
        /**
         * 处理响应
         *
         * @param response httpResponse
         * @throws Exception 异常
         */
        void handle(CloseableHttpResponse response) throws Exception;
    }

}
