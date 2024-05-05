Declare @startPeriod datetime = DATEADD(DAY, 1,EOMONTH(GETDATE(), -2)),
@endPeriod datetime = EOMONTH(GETDATE(), -1)
 
Declare @period datetime = @startPeriod
 
IF EXISTS (select * from sysobjects where name = 'rekap_account_balance')
DROP TABLE rekap_account_balance
 
CREATE TABLE [dbo].[rekap_account_balance](
  [id] bigint IDENTITY(1,1) PRIMARY KEY,
  [as_of] [varchar](10) NULL,
  [aid] [varchar](6) NULL,
  [security_code] [varchar](20) NULL,
  [name] [varchar](255) NULL,
  [balance] [float] NULL,
  [cif_number] [varchar](255) NULL,
  [fee] [float] NULL
) ON [PRIMARY]
 
 
While @period <= @endPeriod
  BEGIN
    insert into rekap_account_balance
    SELECT
           CONVERT(Varchar, @period, 23) "As Of",
           Y.aid,
           Z.securityCode "Security Code",
           Y.fullName "Name",
           X.balance "Balance",
           Z.cifNumber "CIF Number",
           case when left(trim(Y.aid), 1) in ('1','2','3','4') then
                ROUND((X.balance * (bdi.fee_value/100))/360, 2)
            when left(trim(Y.aid), 1) in ('5') then
                ROUND((X.balance * (bina.fee_value/100))/360, 2)
            when left(trim(Y.aid), 1) in ('7') then
                ROUND((X.balance * (ctbc.fee_value/100))/360, 2)
            else 0 end as fee
    FROM rb_portfolio_balance_changes X
           RIGHT JOIN (
                      SELECT A.portfolioCode, B.fullName,B.aid,MAX(A.id) "id"
                      FROM rb_portfolio_balance_changes A
                             LEFT JOIN(
                                      SELECT pbc.portfolioCode, MAX(transactionDate) "transactionDate", mp.fullName, mp.aid
                                      FROM rb_portfolio_balance_changes pbc
                                             LEFT JOIN rb_portfolio p ON pbc.portfolioCode = p.code
                                             left join rb_portfolio_balance pb on p.code = pb.portfolioCode
                                             left join rb_security rs on rs.code = p.securityCode
                                            --  left join csa_master_portfolio mp on p.aid = mp.id
                                             left join csa_master_portfolio mp on p.aid_id = mp.id
                                      WHERE CONVERT(DATE, transactionDate,23) <= CONVERT(DATE, @period,23) --<= '2021-01-29' --AND p.code = '14AMTO'
                                        and rs.status = 'ACTIVE'
                                        and len(mp.aid) = 6
                                        and left(trim(mp.aid), 1) in ('1','2','3','4','5','7')
                                        -- and mp.aid in ('1470FU')
                                      GROUP BY pbc.portfolioCode, mp.fullName, mp.aid
                                      ) B ON A.portfolioCode = B.portfolioCode AND A.transactionDate = B.transactionDate
                      WHERE B.portfolioCode IS NOT NULL
                      GROUP BY A.portfolioCode, B.fullName, B.aid) Y
             ON X.id = Y.id AND Y.id IS NOT NULL
           LEFT JOIN rb_portfolio Z ON Y.portfolioCode = Z.code
           LEFT JOIN billing_fee_param bdi ON 1=1 AND bdi.fee_name = 'Customer Fee BDI'
           LEFT JOIN billing_fee_param bina ON 1=1 AND bina.fee_name = 'Customer Fee BINA'
           LEFT JOIN billing_fee_param ctbc ON 1=1 AND ctbc.fee_name = 'Customer Fee CTBC'
    WHERE X.balance > 0
    ORDER BY "As Of", Y.aid, Z.securityCode
 
    set @period = Dateadd(day, 1, @period)
  END