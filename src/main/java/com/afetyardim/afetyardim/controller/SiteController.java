package com.afetyardim.afetyardim.controller;

import com.afetyardim.afetyardim.dto.SiteDTO;
import com.afetyardim.afetyardim.mapper.SiteMapper;
import com.afetyardim.afetyardim.model.Site;
import com.afetyardim.afetyardim.service.SiteService;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sites")
@AllArgsConstructor
public class SiteController {

  private final SiteService siteService;

//  @PostMapping
//  public Site createSite(@RequestBody Site newSite) {
//    newSite.setId(0);
//    newSite.setCreateDateTime(null);
//    return siteService.createSite(newSite);
//  }

  @GetMapping
  public Collection<Site> getSites(@RequestParam Optional<String> cityFilter) {
    Collection<Site> sites = siteService.getSites(cityFilter);
    return sites;
  }

//  @GetMapping("/{siteId}")
//  public Site getSite(@PathVariable long siteId) {
//    return siteService.getSite(siteId);
//  }

//  @PostMapping("/{siteId}/updates")
//  public Site addSiteUpdate(@PathVariable long siteId, @RequestBody SiteUpdate newSiteUpdate) {
//    newSiteUpdate.setCreateDateTime(LocalDateTime.now());
//    return siteService.addSiteUpdate(siteId, newSiteUpdate);
//  }

  @GetMapping("/v2")
  public List<SiteDTO> getSitesV2(@RequestParam Optional<String> cityFilter) {
    Collection<Site> sites = siteService.getSites(cityFilter);
    return sites.stream().map(SiteMapper::convertModelToDTO).toList();
  }

}
