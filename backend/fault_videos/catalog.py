"""
Reference topology and domain catalog for Rețele Electrice România (PPC Group).
Covers the three distribution regions defined in `db/migration/V1__baseline.sql`:
  - MUNTENIA (București, Ilfov, Giurgiu)
  - BANAT    (Timiș, Arad, Caraș-Severin, Hunedoara)
  - DOBROGEA (Constanța, Tulcea, Călărași, Ialomița)
"""

from __future__ import annotations
from dataclasses import dataclass
from typing import Dict, List


REGIONS: List[str] = ["MUNTENIA", "BANAT", "DOBROGEA"]


@dataclass(frozen=True)
class Substation:
    code: str
    name: str
    region: str
    county: str
    locality: str
    voltage_kv: int
    lat: float
    lon: float
    customers_served: int


@dataclass(frozen=True)
class PowerLine:
    code: str
    name: str
    region: str
    county: str
    voltage_kv: int
    length_km: float
    start_lat: float
    start_lon: float
    end_lat: float
    end_lon: float


@dataclass(frozen=True)
class DroneUnit:
    drone_id: str
    model: str
    region: str
    base_locality: str


SUBSTATIONS_BY_REGION: Dict[str, List[Substation]] = {
    "MUNTENIA": [
        Substation(
            code="ST-MUN-BUC-NORD-110",
            name="Stația 110/20 kV București Nord",
            region="MUNTENIA",
            county="București",
            locality="Sector 1",
            voltage_kv=110,
            lat=44.4892,
            lon=26.0841,
            customers_served=28500,
        ),
        Substation(
            code="ST-MUN-OBOR-110",
            name="Stația 110/20/10 kV Obor",
            region="MUNTENIA",
            county="București",
            locality="Sector 2",
            voltage_kv=110,
            lat=44.4511,
            lon=26.1296,
            customers_served=34200,
        ),
        Substation(
            code="ST-MUN-OTOPENI-110",
            name="Stația 110/20 kV Otopeni",
            region="MUNTENIA",
            county="Ilfov",
            locality="Otopeni",
            voltage_kv=110,
            lat=44.5514,
            lon=26.0772,
            customers_served=16400,
        ),
        Substation(
            code="ST-MUN-DOMNESTI-110",
            name="Stația 110/20 kV Domnești",
            region="MUNTENIA",
            county="Ilfov",
            locality="Domnești",
            voltage_kv=110,
            lat=44.4008,
            lon=25.9153,
            customers_served=12800,
        ),
        Substation(
            code="ST-MUN-GIURGIU-NORD-110",
            name="Stația 110/20 kV Giurgiu Nord",
            region="MUNTENIA",
            county="Giurgiu",
            locality="Giurgiu",
            voltage_kv=110,
            lat=43.9037,
            lon=25.9699,
            customers_served=14100,
        ),
    ],
    "BANAT": [
        Substation(
            code="ST-BAN-TIM-CET-110",
            name="Stația 110/20 kV Timișoara CET",
            region="BANAT",
            county="Timiș",
            locality="Timișoara",
            voltage_kv=110,
            lat=45.7219,
            lon=21.1965,
            customers_served=24600,
        ),
        Substation(
            code="ST-BAN-DUMBRAVITA-110",
            name="Stația 110/20 kV Dumbrăvița",
            region="BANAT",
            county="Timiș",
            locality="Dumbrăvița",
            voltage_kv=110,
            lat=45.7998,
            lon=21.2441,
            customers_served=15200,
        ),
        Substation(
            code="ST-BAN-ARAD-VEST-110",
            name="Stația 110/20 kV Arad Vest",
            region="BANAT",
            county="Arad",
            locality="Arad",
            voltage_kv=110,
            lat=46.1866,
            lon=21.2855,
            customers_served=19800,
        ),
        Substation(
            code="ST-BAN-RESITA-SUD-110",
            name="Stația 110/20 kV Reșița Sud",
            region="BANAT",
            county="Caraș-Severin",
            locality="Reșița",
            voltage_kv=110,
            lat=45.2921,
            lon=21.8892,
            customers_served=11900,
        ),
        Substation(
            code="ST-BAN-DEVA-EST-110",
            name="Stația 110/20 kV Deva Est",
            region="BANAT",
            county="Hunedoara",
            locality="Deva",
            voltage_kv=110,
            lat=45.8763,
            lon=22.9144,
            customers_served=14500,
        ),
    ],
    "DOBROGEA": [
        Substation(
            code="ST-DOB-CTA-PORT-110",
            name="Stația 110/20 kV Constanța Port",
            region="DOBROGEA",
            county="Constanța",
            locality="Constanța",
            voltage_kv=110,
            lat=44.1598,
            lon=28.6348,
            customers_served=26900,
        ),
        Substation(
            code="ST-DOB-MEDGIDIA-110",
            name="Stația 110/20 kV Medgidia",
            region="DOBROGEA",
            county="Constanța",
            locality="Medgidia",
            voltage_kv=110,
            lat=44.2504,
            lon=28.2618,
            customers_served=12300,
        ),
        Substation(
            code="ST-DOB-TULCEA-VEST-110",
            name="Stația 110/20 kV Tulcea Vest",
            region="DOBROGEA",
            county="Tulcea",
            locality="Tulcea",
            voltage_kv=110,
            lat=45.1716,
            lon=28.7814,
            customers_served=15400,
        ),
        Substation(
            code="ST-DOB-CALARASI-NORD-110",
            name="Stația 110/20 kV Călărași Nord",
            region="DOBROGEA",
            county="Călărași",
            locality="Călărași",
            voltage_kv=110,
            lat=44.2051,
            lon=27.3189,
            customers_served=13100,
        ),
        Substation(
            code="ST-DOB-SLOBOZIA-110",
            name="Stația 110/20 kV Slobozia",
            region="DOBROGEA",
            county="Ialomița",
            locality="Slobozia",
            voltage_kv=110,
            lat=44.5647,
            lon=27.3636,
            customers_served=11800,
        ),
    ],
}


POWER_LINES_BY_REGION: Dict[str, List[PowerLine]] = {
    "MUNTENIA": [
        PowerLine(
            code="LEA-110kV-MUN-BUC-OTOPENI-01",
            name="LEA 110 kV București Nord – Otopeni C1",
            region="MUNTENIA",
            county="Ilfov",
            voltage_kv=110,
            length_km=14.6,
            start_lat=44.4892,
            start_lon=26.0841,
            end_lat=44.5514,
            end_lon=26.0772,
        ),
        PowerLine(
            code="LEA-110kV-MUN-DOMNESTI-GIURGIU-02",
            name="LEA 110 kV Domnești – Giurgiu Nord",
            region="MUNTENIA",
            county="Giurgiu",
            voltage_kv=110,
            length_km=58.2,
            start_lat=44.4008,
            start_lon=25.9153,
            end_lat=43.9037,
            end_lon=25.9699,
        ),
        PowerLine(
            code="LEA-20kV-MUN-ILFOV-SNAGOV-07",
            name="LEA 20 kV Otopeni – Snagov",
            region="MUNTENIA",
            county="Ilfov",
            voltage_kv=20,
            length_km=22.4,
            start_lat=44.5514,
            start_lon=26.0772,
            end_lat=44.7019,
            end_lon=26.1631,
        ),
    ],
    "BANAT": [
        PowerLine(
            code="LEA-110kV-BAN-TIM-ARAD-01",
            name="LEA 110 kV Timișoara – Arad Vest",
            region="BANAT",
            county="Timiș",
            voltage_kv=110,
            length_km=49.8,
            start_lat=45.7998,
            start_lon=21.2441,
            end_lat=46.1866,
            end_lon=21.2855,
        ),
        PowerLine(
            code="LEA-110kV-BAN-RESITA-CARANSEBES-03",
            name="LEA 110 kV Reșița Sud – Caransebeș",
            region="BANAT",
            county="Caraș-Severin",
            voltage_kv=110,
            length_km=38.5,
            start_lat=45.2921,
            start_lon=21.8892,
            end_lat=45.4144,
            end_lon=22.2169,
        ),
        PowerLine(
            code="LEA-20kV-BAN-HD-HATEG-05",
            name="LEA 20 kV Deva – Hațeg",
            region="BANAT",
            county="Hunedoara",
            voltage_kv=20,
            length_km=31.0,
            start_lat=45.8763,
            start_lon=22.9144,
            end_lat=45.6085,
            end_lon=22.9497,
        ),
    ],
    "DOBROGEA": [
        PowerLine(
            code="LEA-110kV-DOB-CTA-MEDGIDIA-01",
            name="LEA 110 kV Constanța Port – Medgidia",
            region="DOBROGEA",
            county="Constanța",
            voltage_kv=110,
            length_km=36.2,
            start_lat=44.1598,
            start_lon=28.6348,
            end_lat=44.2504,
            end_lon=28.2618,
        ),
        PowerLine(
            code="LEA-110kV-DOB-TULCEA-BABADAG-02",
            name="LEA 110 kV Tulcea Vest – Babadag",
            region="DOBROGEA",
            county="Tulcea",
            voltage_kv=110,
            length_km=34.7,
            start_lat=45.1716,
            start_lon=28.7814,
            end_lat=44.8942,
            end_lon=28.7186,
        ),
        PowerLine(
            code="LEA-20kV-DOB-CALARASI-MODELU-04",
            name="LEA 20 kV Călărași Nord – Modelu",
            region="DOBROGEA",
            county="Călărași",
            voltage_kv=20,
            length_km=16.9,
            start_lat=44.2051,
            start_lon=27.3189,
            end_lat=44.2022,
            end_lon=27.3861,
        ),
    ],
}


DRONES_BY_REGION: Dict[str, List[DroneUnit]] = {
    "MUNTENIA": [
        DroneUnit("DRN-MUN-M350-01", "DJI Matrice 350 RTK + Zenmuse H30T", "MUNTENIA", "Otopeni"),
        DroneUnit("DRN-MUN-M350-02", "DJI Matrice 350 RTK + LiDAR L2", "MUNTENIA", "Domnești"),
    ],
    "BANAT": [
        DroneUnit("DRN-BAN-M350-01", "DJI Matrice 350 RTK + Zenmuse H30T", "BANAT", "Timișoara"),
        DroneUnit("DRN-BAN-M300-02", "DJI Matrice 300 RTK + Zenmuse H20T", "BANAT", "Reșița"),
    ],
    "DOBROGEA": [
        DroneUnit("DRN-DOB-M350-01", "DJI Matrice 350 RTK + Zenmuse H30T", "DOBROGEA", "Constanța"),
        DroneUnit("DRN-DOB-M300-02", "DJI Matrice 300 RTK + Zenmuse H20T", "DOBROGEA", "Tulcea"),
    ],
}


NORMAL_FAULT_CAUSES = [
    ("LOW", "DECLANSARE_TRECATOARE_CREANGA", "Atingere trecătoare vegetație pe LEA 20 kV; RAR reușit."),
    ("LOW", "SUPRATENSIUNE_COMUTATIE", "Supratensiune tranzitorie la manevră baterie condensatoare."),
    ("MEDIUM", "IZOLATOR_CONTURNAT_UMIDITATE", "Conturnare lanț izolatori din cauza depunerilor și umidității ridicate."),
    ("MEDIUM", "DEFECT_MANSON_LES_20KV", "Străpungere manșon cablu subteran 20 kV; preluat pe buclă."),
    ("HIGH", "SCURTCIRCUIT_BIFAZAT_20KV", "Scurtcircuit bifazat pe plecarea 20 kV declanșat prin protecția maximală de curent."),
]

STORM_FAULT_CAUSES = [
    ("HIGH", "FURTUNA_CONDUCTOR_RUPT_VANT", "Conductor rupt pe LEA din cauza rafalelor de vânt > 95 km/h și căderii de arbori."),
    ("CRITICAL", "FURTUNA_TRASNET_DIRECT_110KV", "Descărcare atmosferică directă pe LEA 110 kV; declanșare protecție de distanță treapta I."),
    ("CRITICAL", "FURTUNA_AVARIE_DUBLU_CIRCUIT", "Avarie simultană pe dublu circuit 110 kV în timpul furtunii; insularizare parțială."),
    ("HIGH", "FURTUNA_INUNDATIE_POST_TRAFO", "Inundație în postul de transformare; deconectare preventivă de urgență."),
    ("MEDIUM", "FURTUNA_CHICIURA_GALOPAJ_LEA", "Galopaj conductoare sub acțiunea vântului și depunerilor de gheață."),
]

DISPATCH_OPERATORS_BY_REGION: Dict[str, List[str]] = {
    "MUNTENIA": ["disp.muntenia.popescu", "disp.muntenia.ionescu", "teren.muntenia.echipa04"],
    "BANAT": ["disp.banat.munteanu", "disp.banat.kovacs", "teren.banat.echipa02"],
    "DOBROGEA": ["disp.dobrogea.stanciu", "disp.dobrogea.dumitru", "teren.dobrogea.echipa01"],
}
