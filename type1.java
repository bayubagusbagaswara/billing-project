package com.bayu.billingservice.service.impl;

import com.bayu.billingservice.dto.BillingCalculationErrorMessageDTO;
import com.bayu.billingservice.dto.BillingCalculationResponse;
import com.bayu.billingservice.dto.CoreCalculateRequest;
import com.bayu.billingservice.dto.core.BillingContextDate;
import com.bayu.billingservice.dto.core.CoreType1DTO;
import com.bayu.billingservice.dto.investmentmanagement.InvestmentManagementDTO;
import com.bayu.billingservice.exception.BillingCalculationException;
import com.bayu.billingservice.model.*;
import com.bayu.billingservice.model.enumerator.ApprovalStatus;
import com.bayu.billingservice.model.enumerator.BillingStatus;
import com.bayu.billingservice.model.enumerator.FeeParameter;
import com.bayu.billingservice.repository.BillingCoreRepository;
import com.bayu.billingservice.service.*;
import com.bayu.billingservice.util.ConvertDateUtil;
import com.bayu.billingservice.util.StringUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoreType1ServiceImpl implements CoreType1Service {

    private final BillingCoreRepository billingCoreRepository;
    private final CustomerService customerService;
    private final InvestmentManagementService investmentManagementService;
    private final FeeParameterService feeParameterService;
    private final SkTransactionService skTransactionService;
    private final SfValRgDailyService sfValRgDailyService;
    private final BillingNumberService billingNumberService;
    private final ConvertDateUtil convertDateUtil;

    @Override
    public BillingCalculationResponse calculate(CoreCalculateRequest request) {
        log.info("Start core billing calculation type 1 with a data request: {}", request);

        Integer totalDataSuccess = 0;
        Integer totalDataFailed = 0;
        List<BillingCalculationErrorMessageDTO> errorMessageList = new ArrayList<>();

        Instant dateNow = Instant.now();
        String categoryUpperCase = request.getCategory().toUpperCase();
        String typeUpperCase = StringUtil.replaceBlanksWithUnderscores(request.getType());

        /* get fee parameter VAT fee */
        BigDecimal vatFee = feeParameterService.getValueByName(FeeParameter.VAT.getValue());

        /* generate billing context date */
        BillingContextDate billingContextDate = getBillingContextDate(dateNow);

        /* get all customer Core Type 1 */
        List<Customer> customerList = customerService.getAllByBillingCategoryAndBillingType(categoryUpperCase, typeUpperCase);

        /* calculate billing for all customers */
        for (Customer customer : customerList) {
            try {
                processCustomerBilling(customer, billingContextDate, vatFee);
                totalDataSuccess++;
            } catch (BillingCalculationException e) {
                handleGeneralError(customer.getCustomerCode(), e, errorMessageList);
                totalDataFailed++;
            } catch (Exception e) {
                log.error("Error processing customer code {}: {}", customer.getCustomerCode(), e.getMessage(), e);
                handleGeneralError(customer.getCustomerCode(), e, errorMessageList);
                totalDataFailed++;
            }
        }

        log.info("Total successful calculations: {}, total failed calculations: {}", totalDataSuccess, totalDataFailed);
        return new BillingCalculationResponse(totalDataSuccess, totalDataFailed, errorMessageList);
    }

    private BillingContextDate getBillingContextDate(Instant dateNow) {
        Map<String, String> monthMinus1 = convertDateUtil.getMonthMinus1();
        String monthNameMinus1 = monthMinus1.get("monthName");
        int yearMinus1 = Integer.parseInt(monthMinus1.get("year"));

        Map<String, String> monthNow = convertDateUtil.getMonthNow();
        String monthNameNow = monthNow.get("monthName");
        int yearNow = Integer.parseInt(monthNow.get("year"));

        return new BillingContextDate(dateNow, monthNameMinus1, yearMinus1, monthNameNow, yearNow);
    }

    private void processCustomerBilling(Customer customer, BillingContextDate context, BigDecimal vatFee) {
        String customerCode = customer.getCustomerCode();
        BigDecimal customerSafekeepingFee = customer.getCustomerSafekeepingFee();
        BigDecimal transactionHandlingFee = customer.getCustomerTransactionHandling();
        String billingCategory = customer.getBillingCategory();
        String billingType = customer.getBillingType();
        String miCode = customer.getMiCode();

        InvestmentManagementDTO investmentManagementDTO = investmentManagementService.getByCode(miCode);

        /* TESTING is November 2023 */
        List<SkTransaction> skTransactionList = skTransactionService.getAllByAidAndMonthAndYear(customerCode, "November", 2023);
        List<SfValRgDaily> sfValRgDailyList = sfValRgDailyService.getAllByAidAndMonthAndYear(customerCode, "November", 2023);

        Optional<BillingCore> existingBillingCore = billingCoreRepository.findByCustomerCodeAndBillingCategoryAndBillingTypeAndMonthAndYear(customerCode, billingCategory, billingType, context.getMonthNameMinus1(), context.getYearMinus1());
        if (existingBillingCore.isEmpty() || Boolean.TRUE.equals(!existingBillingCore.get().getPaid())) {
            existingBillingCore.ifPresent(this::deleteExistingBillingCore);

            Integer transactionHandlingValueFrequency = calculateTransactionHandlingValueFrequency(customerCode, skTransactionList);
            BigDecimal transactionHandlingAmountDue = calculateTransactionHandlingAmountDue(customerCode, transactionHandlingFee, transactionHandlingValueFrequency);
            BigDecimal safekeepingValueFrequency = calculateSafekeepingValueFrequency(customerCode, sfValRgDailyList);
            BigDecimal safekeepingAmountDue = calculateSafekeepingAmountDue(customerCode, sfValRgDailyList);
            BigDecimal subTotal = calculateSubTotalAmountDue(customerCode, transactionHandlingAmountDue, safekeepingAmountDue);
            BigDecimal vatAmountDue = calculateVatAmountDue(customerCode, subTotal, vatFee);
            BigDecimal totalAmountDue = calculateTotalAmountDue(customerCode, subTotal, vatAmountDue);

            CoreType1DTO coreType1DTO = new CoreType1DTO(
                    transactionHandlingValueFrequency, transactionHandlingFee, transactionHandlingAmountDue,
                    safekeepingValueFrequency, customerSafekeepingFee, safekeepingAmountDue,
                    subTotal, vatFee, vatAmountDue, totalAmountDue);

            BillingCore billingCore = buildBillingCore(customer, investmentManagementDTO, context, coreType1DTO);

            String number = billingNumberService.generateSingleNumber(context.getMonthNameNow(), context.getYearNow());
            billingCore.setBillingNumber(number);
            billingCoreRepository.save(billingCore);
            billingNumberService.saveSingleNumber(number);
        } else {
            throw new BillingCalculationException("Billing already paid for period " + context.getMonthNameMinus1() + " " + context.getYearMinus1());
        }
    }

    private BillingCore buildBillingCore(Customer customer, InvestmentManagementDTO investmentManagementDTO, BillingContextDate context, CoreType1DTO coreType1DTO) {
        return BillingCore.builder()
                .createdAt(context.getDateNow())
                .updatedAt(context.getDateNow())
                .approvalStatus(ApprovalStatus.PENDING)
                .billingStatus(BillingStatus.GENERATED)
                .customerCode(customer.getCustomerCode())
                .customerName(customer.getCustomerName())
                .month(context.getMonthNameMinus1())
                .year(context.getYearMinus1())
                .billingPeriod(context.getMonthNameMinus1() + " " + context.getYearMinus1())
                .billingStatementDate(ConvertDateUtil.convertInstantToString(context.getDateNow()))
                .billingPaymentDueDate(ConvertDateUtil.convertInstantToStringPlus14Days(context.getDateNow()))
                .billingCategory(customer.getBillingCategory())
                .billingType(customer.getBillingType())
                .billingTemplate(customer.getBillingTemplate())
                .customerMinimumFee(customer.getCustomerMinimumFee())
                .customerSafekeepingFee(customer.getCustomerSafekeepingFee())
                .account(customer.getAccount())
                .accountName(customer.getAccountName())
                .currency(customer.getCurrency())
                .investmentManagementName(investmentManagementDTO.getName())
                .investmentManagementAddress1(investmentManagementDTO.getAddress1())
                .investmentManagementAddress2(investmentManagementDTO.getAddress2())
                .investmentManagementAddress3(investmentManagementDTO.getAddress3())
                .investmentManagementAddress4(investmentManagementDTO.getAddress4())
                .investmentManagementEmail(investmentManagementDTO.getEmail())
                .investmentManagementUniqueKey(investmentManagementDTO.getUniqueKey())
                .transactionHandlingValueFrequency(coreType1DTO.getTransactionHandlingValueFrequency())
                .transactionHandlingFee(coreType1DTO.getTransactionHandlingFee())
                .transactionHandlingAmountDue(coreType1DTO.getTransactionHandlingAmountDue())
                .safekeepingValueFrequency(coreType1DTO.getSafekeepingValueFrequency())
                .safekeepingFee(customer.getCustomerSafekeepingFee())
                .safekeepingAmountDue(coreType1DTO.getSafekeepingAmountDue())
                .subTotal(coreType1DTO.getSubTotal())
                .vatFee(coreType1DTO.getVatFee())
                .vatAmountDue(coreType1DTO.getVatAmountDue())
                .totalAmountDue(coreType1DTO.getTotalAmountDue())
                .gefuCreated(false)
                .paid(false)
                .build();
    }

    private static Integer calculateTransactionHandlingValueFrequency(String aid, List<SkTransaction> skTransactionList) {
        Integer totalTransactionHandling = skTransactionList.size();
        log.info("[Core Type 1] Total transaction handling Aid '{}' is '{}'", aid, totalTransactionHandling);
        return totalTransactionHandling;
    }

    private static BigDecimal calculateTransactionHandlingAmountDue(String aid, BigDecimal transactionHandlingFee, int transactionHandlingValueFrequency) {
        BigDecimal transactionHandlingAmountDue = transactionHandlingFee.multiply(new BigDecimal(transactionHandlingValueFrequency).setScale(0, RoundingMode.HALF_UP));
        log.info("[Core Type 1] Transaction handling amount due Aid '{}' is '{}'", aid, transactionHandlingAmountDue);
        return transactionHandlingAmountDue;
    }

    private static BigDecimal calculateSafekeepingValueFrequency(String aid, List<SfValRgDaily> sfValRgDailyList) {
        List<SfValRgDaily> latestEntries = sfValRgDailyList.stream()
                .filter(entry -> entry.getDate().equals(sfValRgDailyList.stream()
                        .map(SfValRgDaily::getDate)
                        .max(Comparator.naturalOrder())
                        .orElse(null)))
                .toList();
        BigDecimal safekeepingValueFrequency = latestEntries.stream()
                .map(SfValRgDaily::getMarketValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);

        log.info("[Core Type 1] Safekeeping value frequency Aid '{}' is '{}'", aid, safekeepingValueFrequency);
        return safekeepingValueFrequency;
    }

    private static BigDecimal calculateSafekeepingAmountDue(String aid, List<SfValRgDaily> sfValRgDailyList) {
        BigDecimal safekeepingAmountDue = sfValRgDailyList.stream()
                .map(SfValRgDaily::getEstimationSafekeepingFee)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(0, RoundingMode.HALF_UP);

        log.info("[Core Type 1] Safekeeping amount due Aid '{}' is '{}'", aid, safekeepingAmountDue);
        return safekeepingAmountDue;
    }

    private static BigDecimal calculateSubTotalAmountDue(String aid, BigDecimal transactionHandlingAmountDue, BigDecimal safekeepingAmountDue) {
        BigDecimal subTotalAmountDue = transactionHandlingAmountDue.add(safekeepingAmountDue).setScale(0, RoundingMode.HALF_UP);
        log.info("[Core Type 1] Sub total amount due Aid '{}' is '{}'", aid, subTotalAmountDue);
        return subTotalAmountDue;
    }

    private static BigDecimal calculateVatAmountDue(String aid, BigDecimal subTotalAmountDue, BigDecimal vatFee) {
        BigDecimal vatAmountDue = subTotalAmountDue
                .multiply(vatFee)
                .divide(new BigDecimal(100), 0, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.HALF_UP);
        log.info("[Core Type 1] VAT amount due Aid '{}' is '{}'", aid, vatAmountDue);
        return vatAmountDue;
    }

    private static BigDecimal calculateTotalAmountDue(String aid, BigDecimal subTotalAmountDue, BigDecimal vatAmountDue) {
        BigDecimal totalAmountDue = subTotalAmountDue.add(vatAmountDue).setScale(0, RoundingMode.HALF_UP);
        log.info("[Core Type 1] Total amount due Aid '{}' is '{}'", aid, totalAmountDue);
        return totalAmountDue;
    }

    private void deleteExistingBillingCore(BillingCore existBillingCore) {
        String billingNumber = existBillingCore.getBillingNumber();
        billingCoreRepository.delete(existBillingCore);
        billingNumberService.deleteByBillingNumber(billingNumber);
    }

    private void handleGeneralError(String customerCode, Exception e, List<BillingCalculationErrorMessageDTO> errorMessageList) {
        addErrorMessage(errorMessageList, customerCode, e.getMessage());
    }

    private void addErrorMessage(List<BillingCalculationErrorMessageDTO> errorMessageList, String customerCode, String message) {
        List<String> stringList = new ArrayList<>();
        stringList.add(message);
        errorMessageList.add(new BillingCalculationErrorMessageDTO(customerCode, stringList));
    }

    @Override
    public List<BillingCore> getAll() {
        String type = "TYPE_1";
        return billingCoreRepository.findAllByType(type);
    }
}
