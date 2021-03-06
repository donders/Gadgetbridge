package nodomain.freeyourgadget.gadgetbridge.service.devices.pebble.webview;

import android.annotation.TargetApi;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import net.e175.klaus.solarpositioning.DeltaT;
import net.e175.klaus.solarpositioning.SPA;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.model.Weather;

import static nodomain.freeyourgadget.gadgetbridge.util.WebViewSingleton.internetHelper;
import static nodomain.freeyourgadget.gadgetbridge.util.WebViewSingleton.internetHelperBound;
import static nodomain.freeyourgadget.gadgetbridge.util.WebViewSingleton.internetHelperListener;
import static nodomain.freeyourgadget.gadgetbridge.util.WebViewSingleton.internetResponse;
import static nodomain.freeyourgadget.gadgetbridge.util.WebViewSingleton.latch;

public class GBWebClient extends WebViewClient {

    private String[] AllowedDomains = new String[]{
            "openweathermap.org",   //for weather :)
            "tagesschau.de"         //for internal watchapp tests
    };
    private static final Logger LOG = LoggerFactory.getLogger(GBWebClient.class);

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri parsedUri = request.getUrl();
        LOG.debug("WEBVIEW shouldInterceptRequest URL: " + parsedUri.toString());
        WebResourceResponse mimickedReply = mimicReply(parsedUri);
        if (mimickedReply != null)
            return mimickedReply;
        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        LOG.debug("WEBVIEW shouldInterceptRequest URL (legacy): " + url);
        Uri parsedUri = Uri.parse(url);
        WebResourceResponse mimickedReply = mimicReply(parsedUri);
        if (mimickedReply != null)
            return mimickedReply;
        return super.shouldInterceptRequest(view, url);
    }


    private WebResourceResponse mimicReply(Uri requestedUri) {
        if (requestedUri.getHost() != null && (org.apache.commons.lang3.StringUtils.indexOfAny(requestedUri.getHost(), AllowedDomains) != -1)) {
            if (internetHelperBound) {
                LOG.debug("WEBVIEW forwarding request to the internet helper");
                Bundle bundle = new Bundle();
                bundle.putString("URL", requestedUri.toString());
                Message webRequest = Message.obtain();
                webRequest.replyTo = internetHelperListener;
                webRequest.setData(bundle);
                try {
                    latch = new CountDownLatch(1); //the messenger should run on a single thread, hence we don't need to be worried about concurrency. This approach however is certainly not ideal.
                    internetHelper.send(webRequest);
                    latch.await();
                    return internetResponse;

                } catch (RemoteException | InterruptedException e) {
                    LOG.warn("Error downloading data from " + requestedUri, e);
                }

            } else {
                LOG.debug("WEBVIEW request to openweathermap.org detected of type: " + requestedUri.getPath() + " params: " + requestedUri.getQuery());
                return mimicOpenWeatherMapResponse(requestedUri.getPath(), requestedUri.getQueryParameter("units"));
            }
        } else {
            LOG.debug("WEBVIEW request:" + requestedUri.toString() + " not intercepted");
        }
        return null;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        Uri parsedUri = Uri.parse(url);

        if (parsedUri.getScheme().startsWith("http")) {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
            GBApplication.getContext().startActivity(i);
        } else if (parsedUri.getScheme().startsWith("pebblejs")) {
            url = url.replaceFirst("^pebblejs://close#", "file:///android_asset/app_config/configure.html?config=true&json=");
            view.loadUrl(url);
        } else if (parsedUri.getScheme().equals("data")) { //clay
            view.loadUrl(url);
        } else {
            LOG.debug("WEBVIEW Ignoring unhandled scheme: " + parsedUri.getScheme());
        }

        return true;
    }

    private static WebResourceResponse mimicOpenWeatherMapResponse(String type, String units) {

        if (Weather.getInstance() == null || Weather.getInstance().getWeather2() == null) {
            LOG.warn("WEBVIEW - Weather instance is null, cannot update weather");
            return null;
        }

        CurrentPosition currentPosition = new CurrentPosition();

        try {
            JSONObject resp;

            if ("/data/2.5/weather".equals(type) && Weather.getInstance().getWeather2().reconstructedWeather != null) {
                resp = new JSONObject(Weather.getInstance().getWeather2().reconstructedWeather.toString());

                JSONObject main = resp.getJSONObject("main");

                convertTemps(main, units); //caller might want different units

                resp.put("cod", 200);
                resp.put("coord", coordObject(currentPosition));
                resp.put("sys", sysObject(currentPosition));
//            } else if ("/data/2.5/forecast".equals(type) && Weather.getInstance().getWeather2().reconstructedForecast != null) { //this is wrong, as we only have daily data. Unfortunately it looks like daily forecasts cannot be reconstructed
//                resp = new JSONObject(Weather.getInstance().getWeather2().reconstructedForecast.toString());
//
//                JSONObject city = resp.getJSONObject("city");
//                city.put("coord", coordObject(currentPosition));
//
//                JSONArray list = resp.getJSONArray("list");
//                for (int i = 0, size = list.length(); i < size; i++) {
//                    JSONObject item = list.getJSONObject(i);
//                    JSONObject main = item.getJSONObject("main");
//                    convertTemps(main, units); //caller might want different units
//                }
//
//                resp.put("cod", 200);
            } else {
                LOG.warn("WEBVIEW - cannot mimick request of type " + type + " (unsupported or lack of data)");
                return null;
            }

            LOG.info("WEBVIEW - mimic openweather response" + resp.toString());
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return new WebResourceResponse("application/json", "utf-8", 200, "OK",
                        headers,
                        new ByteArrayInputStream(resp.toString().getBytes())
                );
            } else {
                return new WebResourceResponse("application/json", "utf-8", new ByteArrayInputStream(resp.toString().getBytes()));
            }
        } catch (JSONException e) {
            LOG.warn("Error building the JSON weather message.", e);
        }

        return null;

    }


    private static JSONObject sysObject(CurrentPosition currentPosition) throws JSONException {
        GregorianCalendar[] sunrise = SPA.calculateSunriseTransitSet(new GregorianCalendar(), currentPosition.getLatitude(), currentPosition.getLongitude(), DeltaT.estimate(new GregorianCalendar()));

        JSONObject sys = new JSONObject();
        sys.put("country", "World");
        sys.put("sunrise", (sunrise[0].getTimeInMillis() / 1000));
        sys.put("sunset", (sunrise[2].getTimeInMillis() / 1000));

        return sys;
    }

    private static void convertTemps(JSONObject main, String units) throws JSONException {
        if ("metric".equals(units)) {
            main.put("temp", (int) main.get("temp") - 273);
            main.put("temp_min", (int) main.get("temp_min") - 273);
            main.put("temp_max", (int) main.get("temp_max") - 273);
        } else if ("imperial".equals(units)) { //it's 2017... this is so sad
            main.put("temp", ((int) (main.get("temp")) - 273.15f) * 1.8f + 32);
            main.put("temp_min", ((int) (main.get("temp_min")) - 273.15f) * 1.8f + 32);
            main.put("temp_max", ((int) (main.get("temp_max")) - 273.15f) * 1.8f + 32);
        }
    }

    private static JSONObject coordObject(CurrentPosition currentPosition) throws JSONException {
        JSONObject coord = new JSONObject();
        coord.put("lat", currentPosition.getLatitude());
        coord.put("lon", currentPosition.getLongitude());
        return coord;
    }

}
