package io.github.spudhero.shufangtv.cloud;

import org.json.JSONException;
import org.json.JSONObject;

/** Secret session data is serialized only by the encrypted account store. */
public final class CloudAccount {
  public final String providerId, id;
  public String label;
  public final JSONObject data;

  public CloudAccount(String providerId, String id, String label, JSONObject data) {
    this.providerId = providerId;
    this.id = id;
    this.label = label;
    this.data = data;
  }

  public synchronized JSONObject toJson() throws JSONException {
    return new JSONObject()
        .put("provider", providerId)
        .put("id", id)
        .put("label", label)
        .put("data", new JSONObject(data.toString()));
  }

  public static CloudAccount fromJson(JSONObject value) throws JSONException {
    return new CloudAccount(
        value.getString("provider"),
        value.getString("id"),
        value.getString("label"),
        value.getJSONObject("data"));
  }
}
