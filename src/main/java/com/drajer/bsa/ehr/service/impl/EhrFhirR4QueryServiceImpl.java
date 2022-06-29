package com.drajer.bsa.ehr.service.impl;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import com.drajer.bsa.auth.AuthorizationUtils;
import com.drajer.bsa.dao.HealthcareSettingsDao;
import com.drajer.bsa.ehr.service.EhrQueryService;
import com.drajer.bsa.kar.model.FhirQueryFilter;
import com.drajer.bsa.model.HealthcareSetting;
import com.drajer.bsa.model.KarProcessingData;
import com.drajer.bsa.utils.BsaServiceUtils;
import com.drajer.sof.utils.FhirContextInitializer;
import com.drajer.sof.utils.ResourceUtils;
import java.io.File;
import java.io.FileFilter;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DataRequirement;
import org.hl7.fhir.r4.model.DocumentReference;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterLocationComponent;
import org.hl7.fhir.r4.model.Encounter.EncounterParticipantComponent;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 *
 *
 * <h1>EhrQueryService</h1>
 *
 * This class defines the implementation methods to access data from the Ehr for a set of resources.
 *
 * @author nbashyam
 */
@Service
@Transactional
public class EhrFhirR4QueryServiceImpl implements EhrQueryService {

  private final Logger logger = LoggerFactory.getLogger(EhrFhirR4QueryServiceImpl.class);

  private static final String R4 = "R4";
  private static final String PATIENT_RESOURCE = "Patient";
  private static final String PATIENT_ID_SEARCH_PARAM = "?patient=";
  private static final String LOG_FHIR_CTX_GET = " Getting FHIR Context for R4";
  private static final String LOG_INIT_FHIR_CLIENT = "Initializing FHIR Client";

  private static final String EHR_ACCESS_TOKEN = "access_token";
  private static final String EHR_ACCESS_TOKEN_EXPIRES_IN = "expires_in";
  private static final String EHR_ACCESS_TOKEN_PROVIDER_ID = "uuid";

  // Variables for custom queries
  private static final String QUERY_FILE_EXT = "queries";
  private static final String PATIENT_ID_CONTEXT_PARAM = "\\{\\{context.patientId\\}\\}";
  private static final String ENCOUNTER_ID_CONTEXT_PARAM = "\\{\\{context.encounterId\\}\\}";
  private static final String ENCOUNTER_START_DATE_CONTEXT_PARAM =
      "\\{\\{context.encounterStartDate\\}\\}";
  private static final String ENCOUNTER_END_DATE_CONTEXT_PARAM =
      "\\{\\{context.encounterEndDate\\}\\}";
  private static final String SEARCH_QUERY_CHARACTERS = "?";

  /**
   * The attribute stores the custom queries for each KAR.
   *
   * <p>The outer map Key value is the [KAR Id-VersionId] for which the custom queries are
   * applicable. The inner map has the KEY which maps to the DataRequirement Id and the Value maps
   * to the custom query to be applied for data retrieval for the specific data element.
   *
   * <p>For custom queries to be injected, the file name has to match the pattern
   * {[KAR.id]-[versionId].queries}
   *
   * <p>The binding between the KAR file based on version has to be done to the file that contains
   * the queries using some mechanism. So we have chosen the file name to be used to apply the
   * queries.
   */
  private HashMap<String, HashMap<String, String>> customQueries;

  /** The Authorization utils class enables the BSA to get an access token. */
  @Autowired AuthorizationUtils authUtils;

  /** The HealthcareSettings Dao to save Healthcare Setting state as needed */
  @Autowired HealthcareSettingsDao hsDao;

  /** The FHIR Context Initializer necessary to retrieve FHIR resources */
  @Autowired FhirContextInitializer fhirContextInitializer;

  /**
   * The attribute contains the directory of custom query files. Each Kar will have its own file
   * with custom queries.
   */
  @Value("${custom-query.directory}")
  String customQueryDirectory;

  /**
   * The method is used to load the customized queries from the config file to be used instead of
   * default queries from the PlanDefinition.
   *
   * <p>* For custom queries to be injected, the file name has to match the pattern
   * {[PlanDefinition.id]-[versionId].queries}
   *
   * <p>The binding between the PlanDefinition file based on version has to be done to the file that
   * contains the queries using some mechanism. So we have chosen the file name to be used to apply
   * the queries.
   */
  @PostConstruct
  public void initializeCustomQueries() {

    // Load each of the Knowledge Artifact Bundles.
    File folder = new File(customQueryDirectory);

    File[] files = folder.listFiles((FileFilter) FileFilterUtils.fileFileFilter());

    for (File queryFile : files) {

      if (queryFile.isFile()
          && QUERY_FILE_EXT.contentEquals(FilenameUtils.getExtension(queryFile.getName()))) {

        logger.info(" Processing Custom Query File  {}", queryFile.getName());
        processQueryFile(queryFile);
      } // For a File
    }
  }

  private void processQueryFile(File queryFile) {

    // Retrieve the properties.
    HashMap<String, String> queries = new HashMap<>();
    String filenameWithoutExt = FilenameUtils.getBaseName(queryFile.getName());

    logger.info(" Loading Query File {}", queryFile.getAbsolutePath());

    InputStream input = null;
    try {
      input = FileUtils.openInputStream(queryFile);
      Properties prop = new Properties();

      prop.load(input);

      prop.forEach((key, value) -> queries.put((String) key, (String) value));

      if (customQueries != null) {
        if (!customQueries.containsKey(filenameWithoutExt)) {
          customQueries.put(filenameWithoutExt, queries);
        } else {
          logger.error(" Not adding the queries, since it is already initialized ");
        }
      } else {
        customQueries = new HashMap<>();
        customQueries.put(filenameWithoutExt, queries);
      }

    } catch (Exception e) {

      logger.error(" Unable to load file for finalizing custom queries {}", e);
    }
  }

  /**
   * The method is used to retrieve data from the Ehr.
   *
   * @param kd The processing context which contains information such as patient, encounter,
   *     previous data etc.
   * @return The Map of Resources to its type.
   */
  @Override
  public Map<ResourceType, Set<Resource>> getFilteredData(
      KarProcessingData kd, Map<String, ResourceType> resTypes) {

    logger.info(LOG_FHIR_CTX_GET);
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info(LOG_INIT_FHIR_CLIENT);
    IGenericClient client = getClient(kd, context);

    // Get Patient by Id always
    Resource res =
        getResourceById(
            client, context, PATIENT_RESOURCE, kd.getNotificationContext().getPatientId());

    if (res != null) {

      logger.info(
          " Found Patient resource for Id : {}", kd.getNotificationContext().getPatientId());

      Set<Resource> resources = new HashSet<>();
      resources.add(res);
      Map<ResourceType, Set<Resource>> resMap = new EnumMap<>(ResourceType.class);
      resMap.put(res.getResourceType(), resources);
      kd.addResourcesByType(resMap);
    }

    if (kd.getNotificationContext()
        .getNotificationResourceType()
        .equals(ResourceType.Encounter.toString())) {

      logger.info(
          " Fetch Encounter resource for Id : {} ",
          kd.getNotificationContext().getNotificationResourceId());

      Resource enc =
          getResourceById(
              client,
              context,
              ResourceType.Encounter.toString(),
              kd.getNotificationContext().getNotificationResourceId());

      if (enc != null) {

        logger.info(
            " Found Encounter resource for Id : {}",
            kd.getNotificationContext().getNotificationResourceId());

        Set<Resource> resources = new HashSet<>();
        resources.add(enc);
        Map<ResourceType, Set<Resource>> resMap = new EnumMap<>(ResourceType.class);
        resMap.put(enc.getResourceType(), resources);
        kd.addResourcesByType(resMap);
      }
    }

    // Fetch Resources by Patient Id.
    for (Map.Entry<String, ResourceType> entry : resTypes.entrySet()) {

      logger.info(" Fetching Resource of type {}", entry.getValue());

      if (entry.getValue() != ResourceType.Patient && entry.getValue() != ResourceType.Encounter) {
        String url =
            kd.getNotificationContext().getFhirServerBaseUrl()
                + "/"
                + entry.getValue().toString()
                + PATIENT_ID_SEARCH_PARAM
                + kd.getNotificationContext().getPatientId();

        logger.info(" Resource Query Url : {}", url);

        getResourcesByPatientId(
            client,
            context,
            entry.getValue().toString(),
            url,
            kd,
            entry.getValue(),
            entry.getKey());
      }
    }

    // Get other resources for Patient
    return kd.getFhirInputDataByType();
  }

  /**
   * The method is used to retrieve data from the Ehr.
   *
   * @param kd The processing context which contains information such as patient, encounter,
   *     previous data etc.
   * @return The Map of Resources to its type.
   */
  @Override
  public Map<ResourceType, Set<Resource>> getFilteredData(
      KarProcessingData kd, List<DataRequirement> dRequirements) {
    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info("Initializing FHIR Client");
    IGenericClient client = getClient(kd, context);

    // Get Patient by Id always
    Resource res =
        getResourceById(
            client, context, PATIENT_RESOURCE, kd.getNotificationContext().getPatientId());
    if (res != null) {

      logger.info(
          " Found Patient resource for Id : {}", kd.getNotificationContext().getPatientId());

      Set<Resource> resources = new HashSet<>();
      resources.add(res);
      HashMap<ResourceType, Set<Resource>> resMap = new HashMap<>();
      resMap.put(res.getResourceType(), resources);
      kd.addResourcesByType(resMap);
    }

    // Fetch Resources by Patient Id.
    for (DataRequirement entry : dRequirements) {
      String id = entry.getId();
      ResourceType type = ResourceType.valueOf(entry.getType());
      logger.info(" Fetching Resource of type {}", type);

      if (type != ResourceType.Patient && type != ResourceType.Encounter) {
        String url =
            kd.getNotificationContext().getFhirServerBaseUrl()
                + "/"
                + type
                + PATIENT_ID_SEARCH_PARAM
                + kd.getNotificationContext().getPatientId();

        logger.info(" Resource Query Url : {}", url);

        // get the resources
        Set<Resource> resources = kd.getResourcesByType(type.toString());
        if (resources == null || resources.size() == 0) {
          resources = fetchResources(client, context, url);
          kd.addResourcesByType(type, resources);
        }
        // filter resources by any filters in the drRequirements
        Set<Resource> filtered = BsaServiceUtils.filterResources(resources, entry, kd);
        // add filtered resources to kd by type and id
        logger.info("Filtered resource count of type {} dr_id {} is {}", type, id, filtered.size());
        kd.addResourcesById(id, filtered);
      } else {
        kd.addResourcesById(id, kd.getResourcesByType(type.toString()));
      }
    }

    // Get other resources for Patient
    return kd.getFhirInputDataByType();
  }

  /**
   * @param kd The data object for getting the healthcareSetting and notification context from
   * @param context The HAPI FHIR context for making a FHIR client with
   * @return
   */
  public IGenericClient getClient(KarProcessingData kd, FhirContext context) {

    String accessToken = null;

    if (kd.hasValidAccessToken()) {

      accessToken = kd.getAccessToken();
      logger.info(
          " Reusing Valid Access Token: {}, Expiration Time: {}",
          accessToken,
          kd.getHealthcareSetting().getEhrAccessTokenExpirationTime());

    } else {

      retrieveAndUpdateAccessToken(kd);
      accessToken = kd.getAccessToken();
      logger.info(
          " Generated New Access Token: {}, Expiration Time: {}",
          accessToken,
          kd.getHealthcareSetting().getEhrAccessTokenExpirationTime());
    }

    return fhirContextInitializer.createClient(
        context,
        kd.getHealthcareSetting().getFhirServerBaseURL(),
        accessToken,
        kd.getNotificationContext().getxRequestId());
  }

  private void retrieveAndUpdateAccessToken(KarProcessingData data) {

    logger.info(" Retrieving New Access Token since the old one is not valid anymore ");
    JSONObject tokenResponse = authUtils.getToken(data.getHealthcareSetting());

    String accessToken = tokenResponse.getString(EHR_ACCESS_TOKEN);
    int expirationDuration = tokenResponse.getInt(EHR_ACCESS_TOKEN_EXPIRES_IN);

    data.getHealthcareSetting().setEhrAccessToken(accessToken);
    data.getHealthcareSetting().setEhrAccessTokenExpiryDuration(expirationDuration);

    if (tokenResponse.has(EHR_ACCESS_TOKEN_PROVIDER_ID)) {
      data.getHealthcareSetting()
          .setDefaultProviderId(tokenResponse.getString(EHR_ACCESS_TOKEN_PROVIDER_ID));
    }

    long expiresInSec = tokenResponse.getLong(EHR_ACCESS_TOKEN_EXPIRES_IN);
    Instant expirationInstantTime = new Date().toInstant().plusSeconds(expiresInSec);

    data.getHealthcareSetting().setEhrAccessTokenExpirationTime(Date.from(expirationInstantTime));

    hsDao.saveOrUpdate(data.getHealthcareSetting());

    /*
    data.getNotificationContext().setEhrAccessToken(accessToken);
    data.getNotificationContext().setEhrAccessTokenExpiryDuration(expirationDuration);
    data.getNotificationContext().setEhrAccessTokenExpirationTime(Date.from(expirationInstantTime)); */

    // Save both Notification Context and Healthcare Setting.

  }

  /**
   * @param kd The KarProcessingData includes data about the fhir server to create a resource on
   * @param resource the resource to create on the fhir server
   */
  public void createResource(KarProcessingData kd, Resource resource) {

    logger.info(LOG_FHIR_CTX_GET);
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info(LOG_INIT_FHIR_CLIENT);
    IGenericClient client = getClient(kd, context);
    client.create().resource(resource).execute();
  }

  /**
   * @param kd The KarProcessingData includes data about the fhir server to update a resource on
   * @param resource the resource (with ID) to update on the fhir server
   */
  public void updateResource(KarProcessingData kd, Resource resource) {

    logger.info(" Getting FHIR Context for R4");
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info("Initializing FHIR Client");
    IGenericClient client = getClient(kd, context);
    client.update().resource(resource).execute();
  }

  /**
   * @param kd The KarProcessingData which contains information about the fhir server
   * @param resourceType The resource type of the resource to be deleted
   * @param id The logical ID of the resource to be deleted
   */
  public void deleteResource(KarProcessingData kd, ResourceType resourceType, String id) {

    logger.info(LOG_FHIR_CTX_GET);
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info(LOG_INIT_FHIR_CLIENT);
    IGenericClient client = getClient(kd, context);
    client.delete().resourceById(resourceType.toString(), id).execute();
  }

  public HashMap<ResourceType, Set<Resource>> loadJurisdicationData(KarProcessingData kd) {

    logger.info(LOG_FHIR_CTX_GET);
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info(LOG_INIT_FHIR_CLIENT);
    IGenericClient client = getClient(kd, context);

    // Retrieve the encounter
    kd.getContextEncounterId();
    Set<Resource> res = kd.getResourcesByType(ResourceType.Encounter.toString());

    Set<Resource> practitioners = new HashSet<>();
    Set<Resource> locations = new HashSet<>();
    Set<Resource> organizations = new HashSet<>();
    Map<String, String> practitionerMap = new HashMap<>();

    for (Resource r : res) {

      Encounter encounter = (Encounter) r;

      // Load Practitioners
      if (encounter.getParticipant() != null) {

        List<EncounterParticipantComponent> participants = encounter.getParticipant();

        for (EncounterParticipantComponent participant : participants) {
          if (participant.getIndividual() != null) {
            Reference practitionerReference = participant.getIndividual();
            String practitionerID = practitionerReference.getReferenceElement().getIdPart();
            if (!practitionerMap.containsKey(practitionerID)) {
              Practitioner practitioner =
                  (Practitioner)
                      getResourceById(
                          client, context, ResourceType.Practitioner.toString(), practitionerID);
              if (practitioner != null && !practitionerMap.containsKey(practitionerID)) {
                practitioners.add(practitioner);
                practitionerMap.put(practitionerID, ResourceType.Practitioner.toString());
              }
            }
          } // Individual != null
        } // For all participants
      } // For participant != null

      // Load Organizations
      if (Boolean.TRUE.equals(encounter.hasServiceProvider())) {
        Reference organizationReference = encounter.getServiceProvider();
        if (organizationReference.hasReferenceElement()) {
          Organization organization =
              (Organization)
                  getResourceById(
                      client,
                      context,
                      "Organization",
                      organizationReference.getReferenceElement().getIdPart());
          if (organization != null) {
            organizations.add(organization);
          }
        }
      }

      // Load Locations
      if (Boolean.TRUE.equals(encounter.hasLocation())) {
        List<EncounterLocationComponent> enocunterLocations = encounter.getLocation();
        for (EncounterLocationComponent location : enocunterLocations) {
          if (location.getLocation() != null) {
            Reference locationReference = location.getLocation();
            Location locationResource =
                (Location)
                    getResourceById(
                        client,
                        context,
                        "Location",
                        locationReference.getReferenceElement().getIdPart());
            if (locationResource != null) {
              locations.add(locationResource);
            }
          }
        }
      }
    } // for all encounters

    if (!practitioners.isEmpty()) {

      Map<ResourceType, Set<Resource>> resMap = new EnumMap<>(ResourceType.class);
      resMap.put(ResourceType.Practitioner, practitioners);
      kd.addResourcesByType(resMap);
    }

    if (!locations.isEmpty()) {

      Map<ResourceType, Set<Resource>> resMap = new EnumMap<>(ResourceType.class);
      resMap.put(ResourceType.Location, locations);
      kd.addResourcesByType(resMap);
    }

    if (!organizations.isEmpty()) {

      Map<ResourceType, Set<Resource>> resMap = new EnumMap<>(ResourceType.class);
      resMap.put(ResourceType.Organization, organizations);
      kd.addResourcesByType(resMap);
    }

    return kd.getFhirInputDataByType();
  }

  @Override
  public Resource getResourceByUrl(KarProcessingData kd, String resourceName, String url) {

    logger.info(LOG_FHIR_CTX_GET);
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info(LOG_INIT_FHIR_CLIENT);
    IGenericClient client = getClient(kd, context);

    return getResourceByUrl(client, context, resourceName, url);
  }

  public Resource getResourceByUrl(
      IGenericClient genericClient, FhirContext context, String resourceName, String url) {

    Resource resource = null;

    try {

      logger.info("Getting data for Resource : {} with Url : {}", resourceName, url);

      resource = (Resource) (genericClient.read().resource(resourceName).withUrl(url).execute());

    } catch (BaseServerResponseException responseException) {
      if (responseException.getOperationOutcome() != null) {
        logger.debug(
            context
                .newJsonParser()
                .encodeResourceToString(responseException.getOperationOutcome()));
      }
      logger.error("Error in getting {} resource by Id: {}", resourceName, url, responseException);
    } catch (Exception e) {
      logger.error("Error in getting {} resource by Id: {}", resourceName, url, e);
    }
    return resource;
  }

  public Set<Resource> fetchResources(
      IGenericClient genericClient, FhirContext context, String searchUrl) {
    Set<Resource> resources = new HashSet<Resource>();
    Bundle bundle = genericClient.search().byUrl(searchUrl).returnBundle(Bundle.class).execute();
    getAllR4RecordsUsingPagination(genericClient, bundle);
    List<BundleEntryComponent> bc = bundle.getEntry();
    for (BundleEntryComponent comp : bc) {
      resources.add(comp.getResource());
    }
    return resources;
  }

  @Override
  public Resource getResourceById(KarProcessingData kd, String resourceName, String resourceId) {

    logger.info(LOG_FHIR_CTX_GET);
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info(LOG_INIT_FHIR_CLIENT);
    IGenericClient client = getClient(kd, context);

    return getResourceById(client, context, resourceName, resourceId);
  }

  public Resource getResourceById(
      IGenericClient genericClient, FhirContext context, String resourceName, String resourceId) {

    Resource resource = null;

    try {

      logger.info("Getting data for Resource : {} with Id : {}", resourceName, resourceId);

      resource =
          (Resource) (genericClient.read().resource(resourceName).withId(resourceId).execute());

    } catch (BaseServerResponseException responseException) {
      if (responseException.getOperationOutcome() != null) {
        logger.debug(
            context
                .newJsonParser()
                .encodeResourceToString(responseException.getOperationOutcome()));
      }
      logger.error(
          "Error in getting {} resource by Id: {}", resourceName, resourceId, responseException);
    } catch (Exception e) {
      logger.error("Error in getting {} resource by Id: {}", resourceName, resourceId, e);
    }
    return resource;
  }

  public void getResourcesByPatientId(
      IGenericClient genericClient,
      FhirContext context,
      String resourceName,
      String searchUrl,
      KarProcessingData kd,
      ResourceType resType,
      String id) {

    logger.info("Invoking search url : {}", searchUrl);
    Set<Resource> resources = null;
    Map<ResourceType, Set<Resource>> resMap = null;
    HashMap<String, Set<Resource>> resMapById = null;

    try {
      logger.info(
          "Getting {} data using Patient Id: {}",
          resourceName,
          kd.getNotificationContext().getPatientId());

      Bundle bundle = genericClient.search().byUrl(searchUrl).returnBundle(Bundle.class).execute();

      getAllR4RecordsUsingPagination(genericClient, bundle);

      if (bundle.getEntry() != null) {
        logger.info(
            "Total No of Entries {} retrieved : {}", resourceName, bundle.getEntry().size());

        List<BundleEntryComponent> bc = bundle.getEntry();

        if (bc != null) {

          resources = new HashSet<>();
          resMap = new EnumMap<>(ResourceType.class);
          resMapById = new HashMap<>();
          for (BundleEntryComponent comp : bc) {

            logger.debug(" Adding Resource Id : {}", comp.getResource().getId());
            resources.add(comp.getResource());
          }
          Set<Resource> uniqueResources =
              ResourceUtils.deduplicate(resources).stream().collect(Collectors.toSet());
          resMap.put(resType, uniqueResources);
          resMapById.put(id, uniqueResources);
          kd.addResourcesByType(resMap);
          kd.addResourcesById(resMapById);

          logger.info(" Adding {} resources of type : {}", uniqueResources.size(), resType);
        } else {
          logger.error(" No entries found for type : {}", resType);
        }
      } else {
        logger.error(" Unable to retrieve resources for type : {}", resType);
      }

    } catch (BaseServerResponseException responseException) {
      if (responseException.getOperationOutcome() != null) {
        logger.debug(
            context
                .newJsonParser()
                .encodeResourceToString(responseException.getOperationOutcome()));
      }
      logger.info(
          "Error in getting {} resource by Patient Id: {}",
          resourceName,
          kd.getNotificationContext().getPatientId(),
          responseException);
    } catch (Exception e) {
      logger.info(
          "Error in getting {} resource by Patient Id: {}",
          resourceName,
          kd.getNotificationContext().getPatientId(),
          e);
    }
  }

  private void getAllR4RecordsUsingPagination(IGenericClient genericClient, Bundle bundle) {

    if (bundle.hasEntry()) {
      List<BundleEntryComponent> entriesList = bundle.getEntry();
      if (bundle.hasLink() && bundle.getLink(IBaseBundle.LINK_NEXT) != null) {
        logger.info(
            "Found Next Page in Bundle :{}", bundle.getLink(IBaseBundle.LINK_NEXT).getUrl());
        Bundle nextPageBundleResults = genericClient.loadPage().next(bundle).execute();
        entriesList.addAll(nextPageBundleResults.getEntry());
        nextPageBundleResults.setEntry(entriesList);
        getAllR4RecordsUsingPagination(genericClient, nextPageBundleResults);
      }
    }
  }

  public DocumentReference constructR4DocumentReference(
      String payload,
      String patientId,
      String encounterId,
      String providerUuid,
      String rrDocRefMimeType,
      String title,
      String docCode,
      String docDisplayName,
      String docCodeSystem) {
    DocumentReference documentReference = new DocumentReference();

    // Set Doc Ref Status
    documentReference.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);
    documentReference.setDocStatus(DocumentReference.ReferredDocumentStatus.FINAL);

    // Set Doc Ref Type
    CodeableConcept typeCode = new CodeableConcept();
    List<Coding> codingList = new ArrayList<>();
    Coding typeCoding = new Coding();
    typeCoding.setSystem(docCodeSystem);
    typeCoding.setCode(docCode);
    typeCoding.setDisplay(docDisplayName);
    codingList.add(typeCoding);
    typeCode.setCoding(codingList);
    typeCode.setText(docDisplayName);
    documentReference.setType(typeCode);

    // Set Subject
    Reference patientReference = new Reference();
    patientReference.setReference("Patient/" + patientId);
    documentReference.setSubject(patientReference);

    // Set Author
    if (providerUuid != null && !providerUuid.isEmpty()) {
      List<Reference> authorRefList = new ArrayList<>();
      Reference providerReference = new Reference();
      providerReference.setReference("Practitioner/" + providerUuid);
      authorRefList.add(providerReference);
      documentReference.setAuthor(authorRefList);
    }

    // Set Doc Ref Content
    List<DocumentReference.DocumentReferenceContentComponent> contentList = new ArrayList<>();
    DocumentReference.DocumentReferenceContentComponent contentComp =
        new DocumentReference.DocumentReferenceContentComponent();
    Attachment attachment = new Attachment();
    attachment.setTitle(title);
    attachment.setContentType(rrDocRefMimeType);

    if (payload != null && !payload.isEmpty()) {
      attachment.setData(payload.getBytes());
    }
    contentComp.setAttachment(attachment);
    contentList.add(contentComp);
    documentReference.setContent(contentList);

    DocumentReference.DocumentReferenceContextComponent docContextComp =
        new DocumentReference.DocumentReferenceContextComponent();

    // Set Doc Ref Context
    if (encounterId != null && !encounterId.isEmpty()) {

      List<Reference> encounterRefList = new ArrayList<>();
      Reference encounterReference = new Reference();
      encounterReference.setReference("Encounter/" + encounterId);
      encounterRefList.add(encounterReference);
      docContextComp.setEncounter(encounterRefList);
    }

    Period period = new Period();
    period.setStart(new Date());
    period.setEnd(new Date());
    docContextComp.setPeriod(period);
    documentReference.setContext(docContextComp);

    String docRef = FhirContext.forR4().newJsonParser().encodeResourceToString(documentReference);

    logger.debug("DocumentReference Object===========> {}", docRef);

    return documentReference;
  }

  @Override
  public JSONObject getAuthorizationToken(HealthcareSetting hs) {

    return authUtils.getToken(hs);
  }

  @Override
  public void executeQuery(KarProcessingData data, String dataReqId, FhirQueryFilter query) {

    if (!data.isDataAlreadyFetched(dataReqId, query.getRelatedDataId())) {

      logger.info(" Executing Query for DataReqId: {} as it is not already fetched.", dataReqId);

      String queryToExecute = getQuery(data, dataReqId, query);

      logger.info(" Query to be executed before parameter substitution {}", queryToExecute);

      queryToExecute = substituteContextParams(data, queryToExecute);

      logger.info(" Substituted Query to be executed {}", queryToExecute);

      if (isSearchQuery(queryToExecute)) {

        String finalSearchQuery = createSearchUrl(data, queryToExecute);

        logger.info(
            " Executing Search FHIR Query for resource {} with query {}",
            query.getResourceType().toString(),
            finalSearchQuery);
        executeSearchQuery(data, dataReqId, query, finalSearchQuery);

      } else {

        logger.info(" Executing Get Resource by Id for Query {}", queryToExecute);

        Resource res = getResourceByUrl(data, query.getResourceType().toString(), queryToExecute);

        addResourceToContext(data, res, dataReqId);
      }
    } else {
      logger.info(
          " Not retrieving the data since it is already fetched for dataReqId: {}", dataReqId);
    }
  }

  private String createSearchUrl(KarProcessingData data, String queryToExecute) {

    String finalQuery = data.getNotificationContext().getFhirServerBaseUrl() + queryToExecute;

    return finalQuery;
  }

  public void executeSearchQuery(
      KarProcessingData data, String dataReqId, FhirQueryFilter query, String queryToExecute) {

    logger.info(LOG_FHIR_CTX_GET);
    FhirContext context = fhirContextInitializer.getFhirContext(R4);

    logger.info(LOG_INIT_FHIR_CLIENT);
    IGenericClient client = getClient(data, context);

    getResourcesFromSearch(client, context, queryToExecute, data, query, dataReqId);
  }

  private void getResourcesFromSearch(
      IGenericClient genericClient,
      FhirContext context,
      String searchUrl,
      KarProcessingData kd,
      FhirQueryFilter queryFilter,
      String dataReqId) {

    logger.info("Invoking search url : {}", searchUrl);
    String resType = queryFilter.getResourceType().toString();
    Set<Resource> resources = null;
    HashMap<String, Set<Resource>> resMapById = null;

    try {
      logger.info("Getting data for resource type {} using query: {}", resType, searchUrl);

      Bundle bundle = genericClient.search().byUrl(searchUrl).returnBundle(Bundle.class).execute();

      getAllR4RecordsUsingPagination(genericClient, bundle);
      if (bundle.getEntry() != null) {

        logger.info(
            "Total No of Entries for Resource {} retrieved : {}",
            resType,
            bundle.getEntry().size());

        List<BundleEntryComponent> bc = bundle.getEntry();

        resources = new HashSet<>();
        resMapById = new HashMap<>();
        for (BundleEntryComponent comp : bc) {

          logger.debug(" Adding Resource Id : {}", comp.getResource().getId());
          resources.add(comp.getResource());
        }

        resMapById.put(dataReqId, resources);
        kd.addResourcesById(resMapById);

        logger.info(" Adding {} resources of type : {}", resources.size(), resType);
      } else {
        logger.error(" No entries found for type : {}", resType);
      }

    } catch (BaseServerResponseException responseException) {
      if (responseException.getOperationOutcome() != null) {
        logger.debug(
            context
                .newJsonParser()
                .encodeResourceToString(responseException.getOperationOutcome()));
      }
      logger.info("Error in getting {} resource using search query {}", resType, searchUrl);
    } catch (Exception e) {
      logger.info("Error in getting {} resource using search query {}", resType, searchUrl, e);
    }
  }

  private void addResourceToContext(KarProcessingData data, Resource res, String dataReqId) {

    if (res != null) {
      data.addResourceById(dataReqId, res);
      Set<Resource> resources = new HashSet<>();
      resources.add(res);
      data.addResourcesByType(res.getResourceType(), resources);
    } else {
      logger.info(" Resource for dataReqId {} is null, hence not added ", dataReqId);
    }
  }

  private Boolean isSearchQuery(String queryToExecute) {

    return queryToExecute.contains(SEARCH_QUERY_CHARACTERS);
  }

  private String substituteContextParams(KarProcessingData data, String queryToExecute) {

    // Substitute any context parameters.
    String substitutedQuery =
        queryToExecute.replaceAll(PATIENT_ID_CONTEXT_PARAM, data.getContextPatientId());

    substitutedQuery =
        substitutedQuery.replaceAll(ENCOUNTER_ID_CONTEXT_PARAM, data.getContextEncounterId());

    return substitutedQuery;
  }

  private String getQuery(KarProcessingData data, String dataReqId, FhirQueryFilter query) {

    // Get the Kar Id
    String customQueryFile = data.getKarIdForCustomQueries();
    String queryToExecute =
        data.getKar().getQueryForDataRequirement(dataReqId, query.getRelatedDataId());

    if (customQueries.containsKey(customQueryFile)) {

      if (customQueries.get(customQueryFile).containsKey(dataReqId)) {

        queryToExecute = customQueries.get(customQueryFile).get(dataReqId);
        logger.info(" Found a custom query {} for dataReqId {}", queryToExecute, dataReqId);

      } else if (customQueries.get(customQueryFile).containsKey(query.getRelatedDataId())) {
        queryToExecute = customQueries.get(customQueryFile).get(query.getRelatedDataId());
        logger.info(
            " Found a custom query {} for Related dataReqId {}",
            queryToExecute,
            query.getRelatedDataId());
      } else {

        logger.info(
            " No custom query, so using default query {} for dataReqId {}",
            queryToExecute,
            dataReqId);
      }
    } else {

      logger.info(
          " No custom queries for the specific KAR Id, so use default query {} for dataReqId {}",
          queryToExecute,
          dataReqId);
    }

    return queryToExecute;
  }
}
