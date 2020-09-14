package com.drajer.eca.model;

import com.drajer.cda.utils.CdaValidatorUtil;
import com.drajer.cdafromR4.CdaEicrGeneratorFromR4;
import com.drajer.cdafromdstu2.CdaEicrGenerator;
import com.drajer.eca.model.EventTypes.EcrActionTypes;
import com.drajer.eca.model.EventTypes.JobStatus;
import com.drajer.eca.model.EventTypes.WorkflowEvent;
import com.drajer.ecrapp.model.Eicr;
import com.drajer.ecrapp.service.WorkflowService;
import com.drajer.ecrapp.util.ApplicationUtils;
import com.drajer.sof.model.Dstu2FhirData;
import com.drajer.sof.model.FhirData;
import com.drajer.sof.model.LaunchDetails;
import com.drajer.sof.model.R4FhirData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.PlanDefinition.ActionRelationshipType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public class CloseOutEicrAction extends AbstractAction {

	private final Logger logger = LoggerFactory.getLogger(CloseOutEicrAction.class);
	
	@Override
	public void execute(Object obj, WorkflowEvent launchType) {

		logger.info(" **** START Executing Close Out Eicr Action **** ");
				
		if (obj instanceof LaunchDetails) {
		
			LaunchDetails details = (LaunchDetails) obj;
			ObjectMapper mapper = new ObjectMapper();
			PatientExecutionState state = null;

			state = EcaUtils.getDetailStatus(details);
			state.getCloseOutEicrStatus().setActionId(getActionId());
			
			logger.info(" Executing Close Out Eicr Action , Prior Execution State : = {}" , details.getStatus());

			// Handle Conditions
			Boolean conditionsMet = true;
			conditionsMet = matchCondition(details);

			// PreConditions Met, then process related actions.
			Boolean relatedActsDone = true;
			if (conditionsMet) {
				
				logger.info(" PreConditions have been Met, evaluating Related Actions. ");

				if (getRelatedActions() != null && getRelatedActions().size() > 0) {

					List<RelatedAction> racts = getRelatedActions();

					for (RelatedAction ract : racts) {

						if (ract.getRelationship() == ActionRelationshipType.AFTER) {

							// check if the action is completed.
							String actionId = ract.getRelatedAction().getActionId();

							if (!state.hasActionCompleted(actionId)) {

								logger.info(
										" Action {} is not completed , hence this action has to wait ",actionId);
								relatedActsDone = false;
							}
							else {
								logger.info(" Related Action that has been completed : " + actionId);

								// Check if there is any timing constraint that needs to be handled.
								if(ract.getDuration() != null && 
										state.getCreateEicrStatus().getJobStatus() == JobStatus.NOT_STARTED) {

									// Duration is not null, meaning that the create action has to be delayed by the duration.
									logger.info(" Schedule the job for Close Out EICR Action based on the duration.");

									try {

										List<TimingSchedule> tsjobs = getTimingData();

										if(tsjobs != null) {
											for (TimingSchedule ts : tsjobs) {
	
												// TBD : Setup job using TS Timing after testing so that we can test faster.
												// For now setup a default job with 10 seconds.
													
													WorkflowService.scheduleJob(details.getId(), ts, EcrActionTypes.CLOSE_OUT_EICR, details.getStartDate());
											}
										}
										else {
											WorkflowService.scheduleJob(details.getId(), ract.getDuration(), EcrActionTypes.CLOSE_OUT_EICR, details.getStartDate());
										}
										
										state.getCloseOutEicrStatus().setJobStatus(JobStatus.SCHEDULED);
										details.setStatus(mapper.writeValueAsString(state));

										// No need to continue as the job will take over execution.

										logger.info(" **** END Executing Close Out Eicr Action **** ");
										return;
									} catch (JsonProcessingException e) { 
										String msg = "Unable to read/write execution state";
										logger.error(msg);
										e.printStackTrace();
										
										throw new RuntimeException(msg);
									}
								}
								else {

									logger.info( " No need to scheuled job as it has already been scheduled or completed. ");
								}
							}
						}
						else {
							logger.info(" Action {} is related via {}" ,ract.getRelatedAction().getActionId(),ract.getRelationship());							
						}
					}
				}
				
				// Check Timing Data , Dont check if the state is already scheduled meaning the
				// job was scheduled already.
				if (relatedActsDone) {
					
					logger.info(" All Related Actions are completed ");

					if (state.getCloseOutEicrStatus().getJobStatus() == JobStatus.NOT_STARTED) {
						
						logger.info(" Related Actions Done and this action has not started ");
						
						if (getTimingData() != null && getTimingData().size() > 0) {
							
							logger.info(" Timing Data is present , so create a job based on timing data.");
							List<TimingSchedule> tsjobs = getTimingData();

							for (TimingSchedule ts : tsjobs) {

								// TBD : Setup job using TS Timing after testing so that we can test faster.
								// For now setup a default job with 10 seconds.

								WorkflowService.scheduleJob(details.getId(), ts, EcrActionTypes.CLOSE_OUT_EICR, details.getStartDate());
								state.getCloseOutEicrStatus().setJobStatus(JobStatus.SCHEDULED);
								EcaUtils.updateDetailStatus(details, state);
								
								logger.info(" **** END Executing Close Out Eicr Action **** ");
								return;
                
							}

						}
						
						logger.info(" Job Not Scheduled since there is no timing data ");
					}
					else if (state.getCloseOutEicrStatus().getJobStatus() == JobStatus.SCHEDULED && 
							 launchType == WorkflowEvent.SCHEDULED_JOB) {
						
						logger.info(" Creating the Close Out EICR since the job has been scheduled ");
						
						// Check Trigger Codes again in case the data has changed.
						PatientExecutionState newState = EcaUtils.recheckTriggerCodes(details, launchType);
						
						if(newState.getMatchTriggerStatus().getTriggerMatchStatus() && 
						   newState.getMatchTriggerStatus().getMatchedCodes() != null && 
						   newState.getMatchTriggerStatus().getMatchedCodes().size() > 0) {

							// Since the job has started, Execute the job.
							// Call the Loading Queries and create eICR.
							Eicr ecr = EcaUtils.createEicr(details);

							if (ecr != null) {

								newState.getCloseOutEicrStatus().setEicrClosed(true);
								newState.getCloseOutEicrStatus().seteICRId(ecr.getId().toString());
								newState.getCloseOutEicrStatus().setJobStatus(JobStatus.COMPLETED);

								EcaUtils.updateDetailStatus(details, newState);

								logger.info(" **** Printing Eicr from CLOSE OUT EICR ACTION **** ");

								logger.info(ecr.getData());

								String fileName = ActionRepo.getInstance().getLogFileDirectory() + "/"
										+ details.getLaunchPatientId() + "_CloseOutEicrAction"
										+ LocalDateTime.now().getHour() + LocalDateTime.now().getMinute()
										+ LocalDateTime.now().getSecond() + ".xml";
								ApplicationUtils.saveDataToFile(ecr.getData(), fileName);

								logger.info(" **** End Printing Eicr from CLOSE OUT EICR ACTION **** ");

							}
							
						}// Check if Trigger Code Match found 
						else {
							
							logger.info(" **** Trigger Code did not match, hence not creating EICR **** ");
							
							newState.getCloseOutEicrStatus().setEicrClosed(false);
							newState.getCloseOutEicrStatus().seteICRId("0");
							newState.getCloseOutEicrStatus().setJobStatus(JobStatus.COMPLETED);

							EcaUtils.updateDetailStatus(details, newState);
						}
					}
					else {
						logger.info(" Close Out Eicr Action not creating Eicr because state = {}" , state.getCloseOutEicrStatus().getJobStatus());
					}
				}
				else {
					logger.info(" Related Actions are not completed, hence Close Out Action : EICR will not be created. ");
				}

			}
			else {
				
				logger.info(" Conditions not met, hence Close Out Action : Eicr will not be created. ");
			}
		}
		else {
			
			String msg = "Invalid Object passed to Execute method, Launch Details expected, found : " + obj.getClass().getName();
			logger.error(msg);
			
			throw new RuntimeException(msg);		
		}
	}
	
	@Override
	public void print() {
		
		logger.info(" **** Printing CloseOutEicrAction **** ");
		printBase();
		logger.info(" **** End Printing CloseOutEicrAction **** ");
	}
}