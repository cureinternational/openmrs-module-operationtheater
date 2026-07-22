package org.openmrs.module.operationtheater.api.service.impl;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.CareSetting;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Order;
import org.openmrs.OrderType;
import org.openmrs.api.AdministrationService;
import org.openmrs.api.ConceptService;
import org.openmrs.api.EncounterService;
import org.openmrs.api.OrderService;
import org.openmrs.api.impl.BaseOpenmrsService;
import org.openmrs.module.operationtheater.api.dao.SurgicalBlockDAO;
import org.openmrs.module.operationtheater.api.model.SurgicalAppointment;
import org.openmrs.module.operationtheater.api.model.SurgicalBlock;
import org.openmrs.module.operationtheater.api.service.SurgicalBlockService;
import org.openmrs.module.operationtheater.exception.ValidationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SurgicalBlockServiceImpl extends BaseOpenmrsService implements SurgicalBlockService {
	
	private static final Log log = LogFactory.getLog(SurgicalBlockServiceImpl.class);
	
	static final String SURGERY_ORDER_TYPE_NAME = "Surgery Order";
	
	static final String SURGICAL_ORDER_CONCEPT_NAME = "General Surgical Procedure";
	
	static final String SURGERY_SCHEDULING_ENCOUNTER_TYPE_GP = "operationtheater.surgerySchedulingEncounterTypeUuid";
	
	SurgicalBlockDAO surgicalBlockDAO;
	
	private OrderService orderService;
	
	private ConceptService conceptService;
	
	private EncounterService encounterService;
	
	private AdministrationService adminService;
	
	public void setSurgicalBlockDAO(SurgicalBlockDAO surgicalBlockDAO) {
		this.surgicalBlockDAO = surgicalBlockDAO;
	}
	
	public void setOrderService(OrderService orderService) {
		this.orderService = orderService;
	}
	
	public void setConceptService(ConceptService conceptService) {
		this.conceptService = conceptService;
	}
	
	public void setEncounterService(EncounterService encounterService) {
		this.encounterService = encounterService;
	}
	
	public void setAdminService(AdministrationService adminService) {
		this.adminService = adminService;
	}
	
	@Override
	@Transactional
	public SurgicalBlock save(SurgicalBlock surgicalBlock) {
		// Snapshot new appointments BEFORE validation triggers Hibernate flush (which
		// assigns IDs)
		List<SurgicalAppointment> newAppointments = new ArrayList<>();
		for (SurgicalAppointment appointment : surgicalBlock.getSurgicalAppointments()) {
			if (!appointment.getVoided() && appointment.getId() == null) {
				newAppointments.add(appointment);
			}
		}
		validateSurgicalBlock(surgicalBlock);
		
		for (SurgicalAppointment appointment : newAppointments) {
			try {
				Encounter encounter = createSurgerySchedulingEncounter(appointment, surgicalBlock);
				if (encounter != null) {
					Order order = createSurgeryOrder(appointment, encounter, surgicalBlock);
					if (order != null) {
						appointment.setOrder(order);
					}
				}
			}
			catch (Exception e) {
				log.error("Failed to create Surgery Order for appointment " + appointment.getUuid()
				        + "; appointment will be saved without an order",
				    e);
			}
		}
		
		return surgicalBlockDAO.save(surgicalBlock);
	}
	
	@Override
	public void validateSurgicalBlock(SurgicalBlock surgicalBlock) {
		checkForOverlappingSurgicalBlocks(surgicalBlock);
		checkForOverlappingSurgicalAppointmentsForThePatient(surgicalBlock);
	}
	
	@Override
	@Transactional
	public SurgicalBlock getSurgicalBlockWithAppointments(String surgicalBlockUuid) {
		return surgicalBlockDAO.getSurgicalBlockWithAppointments(surgicalBlockUuid);
	}
	
	@Override
	public List<SurgicalBlock> getSurgicalBlocksBetweenStartDatetimeAndEndDatetime(Date startDatetime, Date endDatetime,
	        Boolean includeVoided, Boolean activeBlocks) {
		return surgicalBlockDAO.getSurgicalBlocksFor(startDatetime, endDatetime, null, null, includeVoided, activeBlocks);
	}
	
	private void checkForOverlappingSurgicalAppointmentsForThePatient(SurgicalBlock surgicalBlock) {
		for (SurgicalAppointment surgicalAppointment : surgicalBlock.getSurgicalAppointments()) {
			List<SurgicalAppointment> overlappingSurgicalAppointmentsForPatient = surgicalBlockDAO
			        .getOverlappingSurgicalAppointmentsForPatient(surgicalBlock.getStartDatetime(),
			            surgicalBlock.getEndDatetime(), surgicalAppointment.getPatient(), surgicalBlock.getId());
			if (overlappingSurgicalAppointmentsForPatient.size() > 0) {
				SurgicalAppointment conflictingSurgicalAppointment = overlappingSurgicalAppointmentsForPatient.get(0);
				SurgicalBlock conflictingSurgicalBlock = conflictingSurgicalAppointment.getSurgicalBlock();
				throw new ValidationException(conflictingSurgicalAppointment.getPatient().getGivenName() + " "
				        + conflictingSurgicalAppointment.getPatient().getFamilyName() + " has conflicting appointment at "
				        + conflictingSurgicalBlock.getLocation().getDisplayString() + " with "
				        + conflictingSurgicalBlock.getProvider().getName());
			}
		}
	}
	
	private void checkForOverlappingSurgicalBlocks(SurgicalBlock surgicalBlock) {
		if (surgicalBlock.getEndDatetime().before(surgicalBlock.getStartDatetime())) {
			throw new ValidationException("Surgical Block start date after end date");
		} else if (!getOverlappingSurgicalBlocksForProvider(surgicalBlock).isEmpty()) {
			throw new ValidationException("Surgical Block has conflicting time with existing block(s) for this surgeon");
		} else if (!getOverlappingSurgicalBlocksForLocation(surgicalBlock).isEmpty()) {
			throw new ValidationException("Surgical Block has conflicting time with existing block(s) for this OT");
		}
	}
	
	private List<SurgicalBlock> getOverlappingSurgicalBlocksForProvider(SurgicalBlock surgicalBlock) {
		return surgicalBlockDAO.getOverlappingSurgicalBlocksFor(surgicalBlock.getStartDatetime(),
		    surgicalBlock.getEndDatetime(), surgicalBlock.getProvider(), null, surgicalBlock.getId());
	}
	
	private List<SurgicalBlock> getOverlappingSurgicalBlocksForLocation(SurgicalBlock surgicalBlock) {
		return surgicalBlockDAO.getOverlappingSurgicalBlocksFor(surgicalBlock.getStartDatetime(),
		    surgicalBlock.getEndDatetime(), null, surgicalBlock.getLocation(), surgicalBlock.getId());
	}
	
	private Encounter createSurgerySchedulingEncounter(SurgicalAppointment appointment, SurgicalBlock block) {
		String encounterTypeUuid = adminService.getGlobalProperty(SURGERY_SCHEDULING_ENCOUNTER_TYPE_GP, "");
		if (StringUtils.isBlank(encounterTypeUuid)) {
			log.warn("operationtheater.surgerySchedulingEncounterTypeUuid GP not configured; skipping order creation");
			return null;
		}
		EncounterType encounterType = encounterService.getEncounterTypeByUuid(encounterTypeUuid);
		if (encounterType == null) {
			log.warn(
			    "SURGERY_SCHEDULING encounter type not found for uuid: " + encounterTypeUuid + "; skipping order creation");
			return null;
		}
		Encounter encounter = new Encounter();
		encounter.setPatient(appointment.getPatient());
		encounter.setEncounterType(encounterType);
		encounter.setEncounterDatetime(new Date());
		if (block.getLocation() != null) {
			encounter.setLocation(block.getLocation());
		}
		return encounterService.saveEncounter(encounter);
	}
	
	private Order createSurgeryOrder(SurgicalAppointment appointment, Encounter encounter, SurgicalBlock block) {
		OrderType orderType = orderService.getOrderTypeByName(SURGERY_ORDER_TYPE_NAME);
		if (orderType == null) {
			log.warn("Surgery Order order type not found; skipping order creation");
			return null;
		}
		Concept concept = conceptService.getConceptByName(SURGICAL_ORDER_CONCEPT_NAME);
		if (concept == null) {
			log.warn("General Surgical Procedure concept not found; skipping order creation");
			return null;
		}
		CareSetting careSetting = orderService.getCareSettingByName(CareSetting.CareSettingType.OUTPATIENT.toString());
		if (careSetting == null) {
			log.warn("OUTPATIENT care setting not found; skipping order creation");
			return null;
		}
		if (block.getProvider() == null) {
			log.warn("Surgical block provider is null; skipping order creation");
			return null;
		}
		Order order = new Order();
		order.setPatient(appointment.getPatient());
		order.setEncounter(encounter);
		order.setOrderType(orderType);
		order.setConcept(concept);
		order.setCareSetting(careSetting);
		order.setOrderer(block.getProvider());
		order.setDateActivated(new Date());
		return orderService.saveOrder(order, null);
	}
}
