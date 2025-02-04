package com.afetyardim.afetyardim.service.ankara;

import com.afetyardim.afetyardim.model.ActiveStatus;
import com.afetyardim.afetyardim.model.Location;
import com.afetyardim.afetyardim.model.Site;
import com.afetyardim.afetyardim.model.SiteStatus;
import com.afetyardim.afetyardim.model.SiteStatusType;
import com.afetyardim.afetyardim.model.SiteUpdate;
import com.afetyardim.afetyardim.service.SiteService;
import com.afetyardim.afetyardim.service.common.SpreadSheetUtils;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.Color;
import com.google.api.services.sheets.v4.model.RowData;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnkaraGoogleSheetsService {

  @Value("${google.api.key}")
  private String API_KEY;

  private final SiteService siteService;
  private final SpreadSheetUtils spreadSheetUtils;

  private final static String ANKARA_SPREAD_SHEET_ID = "1TT7DbGj6F6BN10PS0PkSLAXXLyX9i-ILlBEs70X-Lac";

  //TODO: Increase spread sheet range
  private final static String ANKARA_SPREAD_SHEET_RANGE = "A1:H150";

  private final static String CITY_NAME = "Ankara";


  public void updateSitesForAnkaraSpreadSheet() throws IOException {

    log.info("Start ankara spread sheet update");

    Collection<Site> ankaraSites = siteService.getSites(Optional.of("Ankara"));
    Spreadsheet spreadsheet = getSpreadSheet(ANKARA_SPREAD_SHEET_ID, ANKARA_SPREAD_SHEET_RANGE);

    List<RowData> rows = spreadsheet.getSheets().get(0).getData().get(0).getRowData();
    //Remove first 2 rows of header rows
    rows.remove(0);
    rows.remove(0);

    List<Site> newSites = new ArrayList<>();

    for (int i = 0; i < rows.size() - 2; ) {

      RowData nameRow = rows.get(i);
      RowData activeRow = rows.get(i + 1);
      RowData noteRow = rows.get(i + 2);
      try {
        Optional<Site> newSite = updateOrCreateNewSite(nameRow, activeRow, noteRow, ankaraSites);
        if(newSite.isPresent()){
          newSites.add(newSite.get());
        }
      } catch (Exception exception) {

        String siteName = "COULD_NOT_COMPUTE_SITE_NAME";
        try {
          String calculatedSiteName = (String) nameRow.getValues().get(0).get("formattedValue");
          if (calculatedSiteName != null) {
            siteName = calculatedSiteName;
          }
        } catch (Exception loggingException) {
          log.error("Failed to print error log for exception: ", exception, loggingException);
        }
        log.warn("Failed to parse rowData while parsing Ankara spreadsheet: Site name: {} Exception: {} RowData: {}",
            siteName, exception, nameRow);
      }

      i = Math.min(i + 3, rows.size());

    }

    log.info("Ankara - Rows {}, New sites {}, Previous site count {}",rows.size(),newSites.size(),ankaraSites.size());
    ankaraSites.addAll(newSites);
    siteService.saveAllSites(ankaraSites);
  }

  //İsim,aktiflik,malzeme,insan,gıda,koli,konum, not, 7
  private Optional<Site> updateOrCreateNewSite(RowData nameRow, RowData activeRow, RowData noteRow,
                                               Collection<Site> ankaraSites) {

    String siteName = (String) nameRow.getValues().get(0).get("formattedValue");
    if (siteName == null) {
      return Optional.empty();
    }

    Color activeColor = activeRow.getValues().get(1).getUserEnteredFormat().getBackgroundColor();
    ActiveStatus activeStatus = convertColorToActive(activeColor);
    String activeNote = (String) activeRow.getValues().get(1).get("formattedValue");

    Color materialColor = nameRow.getValues().get(1).getUserEnteredFormat().getBackgroundColor();
    SiteStatus.SiteStatusLevel materialLevel = convertToSiteStatusLevel(materialColor);

    Color humanNeed = nameRow.getValues().get(2).getUserEnteredFormat().getBackgroundColor();
    SiteStatus.SiteStatusLevel humanNeedLevel = convertToSiteStatusLevel(humanNeed);

    Color foodColor = nameRow.getValues().get(3).getUserEnteredFormat().getBackgroundColor();
    SiteStatus.SiteStatusLevel foodLevel = convertToSiteStatusLevel(foodColor);

    Color packageColor = nameRow.getValues().get(4).getUserEnteredFormat().getBackgroundColor();
    SiteStatus.SiteStatusLevel packageLevel = convertToSiteStatusLevel(packageColor);

//    String location = (String) noteRow.getValues().get(6).get("formattedValue");
    String note = (String) noteRow.getValues().get(0).get("formattedValue");

    //People are adding extra characters to sitename
    Optional<Site> existingSite =
        ankaraSites.stream().filter(
            site -> siteName.toLowerCase().contains(site.getName().toLowerCase()) ||
                site.getName().toLowerCase().contains(siteName.toLowerCase())
        ).findAny();

    if (existingSite.isPresent()) {
      Site site = existingSite.get();
      List<SiteStatus> newSiteStatuses = generateSiteStatus(materialLevel, humanNeedLevel, foodLevel, packageLevel);
      site.setLastSiteStatuses(newSiteStatuses);
      site.setActive(activeStatus == ActiveStatus.ACTIVE);
      site.setActiveStatus(activeStatus);

      Optional<SiteUpdate> newSiteUpdate = generateNewSiteUpdate(site, newSiteStatuses, activeNote, note);
      if (newSiteUpdate.isPresent()) {
        site.getUpdates().add(newSiteUpdate.get());
      }else {
        if(!site.didSiteHaveUpdateInLastPeriod() && site.getActiveStatus() != ActiveStatus.UNKNOWN){
          log.warn("Site {} did not get any update in last 24 hours. Moving to unknown state.", site.getName());
          site.setActiveStatus(ActiveStatus.UNKNOWN);
        }
      }
    } else {

      String siteMapsUrl = nameRow.getValues().get(0).getHyperlink();
      //Cant create new site if we don't have the googlemaps url
      if(siteMapsUrl == null){
        return Optional.empty();
      }
      Optional<Location> location = buildSiteLocation(siteMapsUrl);
      if(location.isPresent()){
        Site site = new Site();
        site.setName(siteName);
        site.setActive(activeStatus == ActiveStatus.ACTIVE);
        site.setActiveStatus(activeStatus);
        site.setDescription(siteName);
        site.setLocation(location.get());
        return Optional.of(site);
      }
    }
    return Optional.empty();
  }

  private Optional<Location> buildSiteLocation(String mapUrl) {

    if (Objects.isNull(mapUrl)) {
      return Optional.empty();
    }
    String district = "Bilinmiyor";
    Location location = new Location();
    location.setDistrict(district);
    location.setCity(CITY_NAME);
    location.setAdditionalAddress("Bu alana adres tarifi al butonunu kullanınız.");
    try {
      List<Double> coordinates = spreadSheetUtils.getCoordinatesByUrl(mapUrl);
      location.setLatitude(coordinates.get(0));
      location.setLongitude(coordinates.get(1));
    } catch (Exception exception) {
      log.error("Could not get coordinates by map url {}", mapUrl);
      return Optional.empty();
    }
    return Optional.of(location);
  }

  private Optional<SiteUpdate> generateNewSiteUpdate(Site site,
                                                     List<SiteStatus> siteStatuses,
                                                     String activeNote,
                                                     String note) {

    String concatenatedNote = activeNote + " - " + note;

    if (site.getUpdates().size() != 0 &&
        site.getUpdates().get(site.getUpdates().size() - 1).getUpdate().equals(concatenatedNote)) {
      return Optional.empty();
    }

    SiteUpdate newSiteUpdate = new SiteUpdate();
    newSiteUpdate.setUpdate(concatenatedNote);
    newSiteUpdate.setSiteStatuses(siteStatuses);
    return Optional.of(newSiteUpdate);
  }

  private List<SiteStatus> generateSiteStatus(SiteStatus.SiteStatusLevel materialLevel,
                                              SiteStatus.SiteStatusLevel humanNeedLevel,
                                              SiteStatus.SiteStatusLevel foodLevel,
                                              SiteStatus.SiteStatusLevel packageLevel) {

    return List.of(new SiteStatus(SiteStatusType.MATERIAL, materialLevel),
        new SiteStatus(SiteStatusType.HUMAN_HELP, humanNeedLevel),
        new SiteStatus(SiteStatusType.FOOD, foodLevel),
        new SiteStatus(SiteStatusType.PACKAGE, packageLevel));
  }

  private ActiveStatus convertColorToActive(Color color) {

    Color activeColor1 = new Color();
    activeColor1.setGreen(1.0f);


    Color activeColor2 = new Color();
    activeColor2.setBlue(0.49019608f);
    activeColor2.setGreen(0.76862746f);
    activeColor2.setRed(0.5764706f);

    Color notActiveColor = new Color();
    notActiveColor.setRed(0.6f);
    notActiveColor.setGreen(0.6f);
    notActiveColor.setBlue(0.6f);

    if (color.equals(activeColor1) || color.equals(activeColor2)) {
      return ActiveStatus.ACTIVE;
    }
    if(color.equals(notActiveColor)){
      return ActiveStatus.NOT_ACTIVE;
    }
    return ActiveStatus.UNKNOWN;
  }

  private SiteStatus.SiteStatusLevel convertToSiteStatusLevel(Color color) {

    if (color == null) {
      return SiteStatus.SiteStatusLevel.UNKNOWN;
    }


    // Orange, level 3 , medium need
    if (color.getGreen() != null && compareFloats(color.getGreen(), Float.valueOf(0.6f))) {
      if (color.getRed() != null && compareFloats(color.getRed(), Float.valueOf(1.0f))) {
        return SiteStatus.SiteStatusLevel.NEED_REQUIRED;
      }
    }

    // Yellow, level 2
    if (color.getGreen() != null && compareFloats(color.getGreen(), Float.valueOf(1.0f))) {
      if (color.getRed() != null && compareFloats(color.getRed(), Float.valueOf(1.0f))) {
        return SiteStatus.SiteStatusLevel.NEED_REQUIRED;
      }
    }

    //Red , level 4, urgent need
    if (color.getRed() != null && compareFloats(color.getRed(), Float.valueOf(1.0f))) {
      return SiteStatus.SiteStatusLevel.URGENT_NEED_REQUIRED;
    }

    // Green, level 1
    if (color.getGreen() != null && compareFloats(color.getGreen(), Float.valueOf(1.0f))) {
      return SiteStatus.SiteStatusLevel.NO_NEED_REQUIRED;
    }

    // Blue, no info
    if (color.getBlue() != null && compareFloats(color.getBlue(), Float.valueOf(1.0f))) {
      return SiteStatus.SiteStatusLevel.NO_NEED_REQUIRED;
    }
    return SiteStatus.SiteStatusLevel.UNKNOWN;
  }

  public Spreadsheet getSpreadSheet(String spreadsheetId, String range) throws IOException {

    List<String> ranges = List.of(range);

    boolean includeGridData = true;

    Sheets sheetsService = getSheets();
    Sheets.Spreadsheets.Get request = sheetsService.spreadsheets().get(spreadsheetId);
    request.setRanges(ranges);
    request.setIncludeGridData(includeGridData);
    return request.execute();

  }

  private Sheets getSheets() {
    NetHttpTransport transport = new NetHttpTransport.Builder().build();
    JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
    HttpRequestInitializer httpRequestInitializer = request -> {
      request.setInterceptor(intercepted -> intercepted.getUrl().set("key", API_KEY));
    };

    return new Sheets.Builder(transport, jsonFactory, httpRequestInitializer)
        .setApplicationName("s")
        .build();
  }

  private boolean compareFloats(Float float1, Float fLoat2) {

    double threshold = 0.00001;
    return (Math.abs(float1 - fLoat2) < threshold);

  }
}