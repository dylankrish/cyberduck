/*
 * Storegate.Web
 * No description provided (generated by Swagger Codegen https://github.com/swagger-api/swagger-codegen)
 *
 * OpenAPI spec version: v4
 * 
 *
 * NOTE: This class is auto generated by the swagger code generator program.
 * https://github.com/swagger-api/swagger-codegen.git
 * Do not edit the class manually.
 */


package ch.cyberduck.core.storegate.io.swagger.client.model;

import java.util.Objects;
import java.util.Arrays;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

/**
 * 
 */
@ApiModel(description = "")
@javax.annotation.Generated(value = "io.swagger.codegen.languages.JavaClientCodegen", date = "2019-04-02T17:31:35.366+02:00")
public class CampaignPrice {
  @JsonProperty("startFee")
  private Double startFee = null;

  @JsonProperty("monthlyFee")
  private Double monthlyFee = null;

  @JsonProperty("length")
  private Integer length = null;

  public CampaignPrice startFee(Double startFee) {
    this.startFee = startFee;
    return this;
  }

   /**
   * 
   * @return startFee
  **/
  @ApiModelProperty(value = "")
  public Double getStartFee() {
    return startFee;
  }

  public void setStartFee(Double startFee) {
    this.startFee = startFee;
  }

  public CampaignPrice monthlyFee(Double monthlyFee) {
    this.monthlyFee = monthlyFee;
    return this;
  }

   /**
   * 
   * @return monthlyFee
  **/
  @ApiModelProperty(value = "")
  public Double getMonthlyFee() {
    return monthlyFee;
  }

  public void setMonthlyFee(Double monthlyFee) {
    this.monthlyFee = monthlyFee;
  }

  public CampaignPrice length(Integer length) {
    this.length = length;
    return this;
  }

   /**
   * 
   * @return length
  **/
  @ApiModelProperty(value = "")
  public Integer getLength() {
    return length;
  }

  public void setLength(Integer length) {
    this.length = length;
  }


  @Override
  public boolean equals(java.lang.Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CampaignPrice campaignPrice = (CampaignPrice) o;
    return Objects.equals(this.startFee, campaignPrice.startFee) &&
        Objects.equals(this.monthlyFee, campaignPrice.monthlyFee) &&
        Objects.equals(this.length, campaignPrice.length);
  }

  @Override
  public int hashCode() {
    return Objects.hash(startFee, monthlyFee, length);
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class CampaignPrice {\n");
    
    sb.append("    startFee: ").append(toIndentedString(startFee)).append("\n");
    sb.append("    monthlyFee: ").append(toIndentedString(monthlyFee)).append("\n");
    sb.append("    length: ").append(toIndentedString(length)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(java.lang.Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

}
